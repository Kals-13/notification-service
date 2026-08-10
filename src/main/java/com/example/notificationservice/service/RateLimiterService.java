package com.example.notificationservice.service;

import com.example.notificationservice.domain.DeliveryAttempt.NotificationChannel;
import com.example.notificationservice.domain.Tenant;
import com.example.notificationservice.repository.TenantRepository;
import io.lettuce.core.RedisCommandExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);
    private static final String KEY_PREFIX = "rate_limit:";
    private static final Duration KEY_TTL = Duration.ofHours(1);
    private static final String FIELD_TOKENS = "tokens";
    private static final String FIELD_LAST_REFILL = "last_refill_time";

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
            HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();

            Map<String, String> state = hashOps.entries(key);
            Instant now = Instant.now();

            double currentTokens;
            Instant lastRefillTime;
            if (state.isEmpty()) {
                currentTokens = maxCapacity;
                lastRefillTime = now;
            } else {
                currentTokens = Double.parseDouble(state.get(FIELD_TOKENS));
                lastRefillTime = Instant.parse(state.get(FIELD_LAST_REFILL));
            }

            long secondsSinceRefill = Duration.between(lastRefillTime, now).getSeconds();
            double tokensToAdd = (secondsSinceRefill / 60.0) * maxCapacity;
            double tokensAvailable = Math.min(currentTokens + tokensToAdd, maxCapacity);

            if (tokensAvailable >= 1) {
                double remaining = tokensAvailable - 1;
                hashOps.put(key, FIELD_TOKENS, String.valueOf(remaining));
                hashOps.put(key, FIELD_LAST_REFILL, now.toString());
                redisTemplate.expire(key, KEY_TTL);
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
            hashOps.put(key, FIELD_TOKENS, String.valueOf(newLimit));
            hashOps.put(key, FIELD_LAST_REFILL, Instant.now().toString());
            redisTemplate.expire(key, KEY_TTL);

            log.info("Updated rate limit for tenant {}, channel {} to {}", tenantId, channel, newLimit);
        } catch (RedisConnectionFailureException | RedisCommandExecutionException e) {
            log.error("Redis unavailable while updating rate limit for tenant {}, channel {}", tenantId, channel, e);
        }
    }

    private int resolveLimit(UUID tenantId, NotificationChannel channel) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

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
