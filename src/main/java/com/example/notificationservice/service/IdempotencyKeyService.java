package com.example.notificationservice.service;

import com.example.notificationservice.exception.DuplicateIdempotencyKeyException;
import com.example.notificationservice.exception.ValidationException;
import io.lettuce.core.RedisCommandExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Prevents duplicate notification sends via caller-supplied idempotency keys. A null key
 * means the caller didn't opt in to idempotency checking, so every method here treats it as
 * "allow the request" rather than an error — same as a Redis outage, which fails open.
 */
@Service
public class IdempotencyKeyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyService.class);
    private static final String KEY_PREFIX = "idempotency:";
    private static final Duration TTL = Duration.ofSeconds(86400);
    private static final int MAX_KEY_LENGTH = 100;

    private final RedisTemplate<String, String> redisTemplate;

    public IdempotencyKeyService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void storeKey(String idempotencyKey, UUID jobId) {
        if (idempotencyKey == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(buildKey(idempotencyKey), jobId.toString(), TTL);
            log.debug("Stored idempotency key {} -> {}", idempotencyKey, jobId);
        } catch (RedisConnectionFailureException | RedisCommandExecutionException e) {
            log.warn("Redis unavailable while storing idempotency key {}, failing open", idempotencyKey, e);
        }
    }

    public Optional<UUID> getJobIdIfExists(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(buildKey(idempotencyKey));
            Optional<UUID> jobId = value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
            log.debug("Idempotency key {} lookup: {}", idempotencyKey, jobId.isPresent() ? "found" : "not found");
            return jobId;
        } catch (RedisConnectionFailureException | RedisCommandExecutionException e) {
            log.warn("Redis unavailable while looking up idempotency key {}, treating as not found", idempotencyKey, e);
            return Optional.empty();
        }
    }

    /**
     * Atomically claims the key for jobId (Redis SETNX), rather than checking then storing as
     * two separate steps — two concurrent callers with the same key both passing a
     * getJobIdIfExists() check before either has stored anything would otherwise both "win".
     */
    public boolean validateAndStore(String idempotencyKey, UUID jobId) {
        if (idempotencyKey == null) {
            return true;
        }
        validateIdempotencyKey(idempotencyKey);

        try {
            ValueOperations<String, String> valueOps = redisTemplate.opsForValue();
            Boolean stored = valueOps.setIfAbsent(buildKey(idempotencyKey), jobId.toString(), TTL);

            if (Boolean.TRUE.equals(stored)) {
                log.debug("Stored idempotency key {} -> {}", idempotencyKey, jobId);
                return true;
            }

            String existingJobId = valueOps.get(buildKey(idempotencyKey));
            throw new DuplicateIdempotencyKeyException(
                    "Duplicate request: idempotency key " + idempotencyKey + " already processed as job " + existingJobId);
        } catch (RedisConnectionFailureException | RedisCommandExecutionException e) {
            log.warn("Redis unavailable while validating idempotency key {}, failing open", idempotencyKey, e);
            return true;
        }
    }

    public boolean validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty()) {
            log.debug("Idempotency key validation failed: key is null or empty");
            throw new ValidationException("Idempotency key must not be null or empty");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            log.debug("Idempotency key validation failed: key exceeds {} characters", MAX_KEY_LENGTH);
            throw new ValidationException("Idempotency key exceeds maximum length of " + MAX_KEY_LENGTH + " characters");
        }
        log.debug("Idempotency key {} passed validation", key);
        return true;
    }

    private String buildKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
