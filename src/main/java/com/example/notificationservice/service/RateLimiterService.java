package com.example.notificationservice.service;

import com.example.notificationservice.domain.DeliveryAttempt.NotificationChannel;
import com.example.notificationservice.domain.Tenant;
import com.example.notificationservice.exception.EntityNotFoundException;
import com.example.notificationservice.repository.TenantRepository;
import io.lettuce.core.RedisCommandExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private static final String KEY_PREFIX = "rate_limit:";
    private static final Duration KEY_TTL = Duration.ofHours(1);
    private static final String FIELD_TOKENS = "tokens";
    private static final String FIELD_LAST_REFILL = "last_refill_time";

    // Runs the whole read-refill-check-consume-write cycle as a single Redis command so
    // concurrent callers can't both read the same starting token count and each decrement
    // from it independently (a lost update that would silently let more requests through
    // than the configured capacity). Returns "1:<remaining>" if a token was consumed, or
    // "0:<available>" if the bucket didn't have one — encoded as a single delimited string
    // to avoid the type-conversion complexity of a Lua table reply.
    private static final RedisScript<String> CHECK_AND_CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local maxCapacity = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])
            local ttlSeconds = ARGV[3]

            local tokens = redis.call('HGET', key, 'tokens')
            local lastRefill = redis.call('HGET', key, 'last_refill_time')

            local currentTokens
            local lastRefillMs
            if tokens == false then
              currentTokens = maxCapacity
              lastRefillMs = now
            else
              currentTokens = tonumber(tokens)
              lastRefillMs = tonumber(lastRefill)
            end

            local elapsedSeconds = (now - lastRefillMs) / 1000.0
            local tokensToAdd = (elapsedSeconds / 60.0) * maxCapacity
            local tokensAvailable = math.min(currentTokens + tokensToAdd, maxCapacity)

            if tokensAvailable >= 1 then
              local remaining = tokensAvailable - 1
              redis.call('HSET', key, 'tokens', tostring(remaining), 'last_refill_time', tostring(now))
              redis.call('EXPIRE', key, ttlSeconds)
              return '1:' .. tostring(remaining)
            else
              return '0:' .. tostring(tokensAvailable)
            end
            """, String.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final TenantRepository tenantRepository;

    public RateLimiterService(RedisTemplate<String, String> redisTemplate, TenantRepository tenantRepository) {
        this.redisTemplate = redisTemplate;
        this.tenantRepository = tenantRepository;
    }

    public boolean checkAndConsume(UUID tenantId, NotificationChannel channel) {
        try {
            int maxCapacity = resolveLimit(tenantId, channel);
            String key = buildKey(tenantId, channel);
            long nowMillis = Instant.now().toEpochMilli();

            String result = redisTemplate.execute(CHECK_AND_CONSUME_SCRIPT, List.of(key),
                    String.valueOf(maxCapacity), String.valueOf(nowMillis), String.valueOf(KEY_TTL.toSeconds()));

            String[] parts = result.split(":", 2);
            boolean allowed = "1".equals(parts[0]);
            double remaining = Double.parseDouble(parts[1]);

            if (allowed) {
                log.debug("Rate limit check passed for tenant {}, channel {}, remaining: {}", tenantId, channel, remaining);
                return true;
            }

            log.info("Rate limit exceeded for tenant {}, channel {}", tenantId, channel);
            return false;
        } catch (RedisConnectionFailureException | RedisCommandExecutionException e) {
            log.error("Redis unavailable while checking rate limit for tenant {}, channel {}, failing open", tenantId, channel, e);
            return true;
        }
    }

    public int getRemainingTokens(UUID tenantId, NotificationChannel channel) {
        try {
            String key = buildKey(tenantId, channel);
            String tokens = (String) redisTemplate.opsForHash().get(key, FIELD_TOKENS);
            if (tokens == null) {
                return 0;
            }
            return (int) Double.parseDouble(tokens);
        } catch (RedisConnectionFailureException | RedisCommandExecutionException e) {
            log.error("Redis unavailable while fetching remaining tokens for tenant {}, channel {}", tenantId, channel, e);
            return 0;
        }
    }

    public void setLimit(UUID tenantId, NotificationChannel channel, int newLimit) {
        try {
            String key = buildKey(tenantId, channel);
            redisTemplate.delete(key);

            HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
            hashOps.putAll(key, Map.of(
                    FIELD_TOKENS, String.valueOf(newLimit),
                    FIELD_LAST_REFILL, String.valueOf(Instant.now().toEpochMilli())));
            redisTemplate.expire(key, KEY_TTL);

            log.info("Updated rate limit for tenant {}, channel {} to {}", tenantId, channel, newLimit);
        } catch (RedisConnectionFailureException | RedisCommandExecutionException e) {
            log.error("Redis unavailable while updating rate limit for tenant {}, channel {}", tenantId, channel, e);
        }
    }

    private int resolveLimit(UUID tenantId, NotificationChannel channel) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantId));

        return switch (channel) {
            case EMAIL -> tenant.getEmailRateLimit();
            case SMS -> tenant.getSmsRateLimit();
            case PUSH -> tenant.getPushRateLimit();
            case INAPP -> tenant.getInappRateLimit();
        };
    }

    private String buildKey(UUID tenantId, NotificationChannel channel) {
        return KEY_PREFIX + tenantId + ":" + channel;
    }
}
