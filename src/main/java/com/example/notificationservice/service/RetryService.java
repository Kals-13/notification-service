package com.example.notificationservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

@Service
public class RetryService {

    private static final Logger log = LoggerFactory.getLogger(RetryService.class);

    private static final long BASE_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 30000;
    private static final double JITTER_MIN = 0.8;
    private static final double JITTER_RANGE = 0.4;

    public enum RetryStrategyType {
        EXPONENTIAL_BACKOFF,
        LINEAR_BACKOFF,
        FIXED_DELAY
    }

    public Duration calculateBackoffDelay(int attemptNumber) {
        long uncappedDelayMs = (long) Math.pow(2, attemptNumber) * BASE_DELAY_MS;
        long jitteredDelayMs = getJitter(uncappedDelayMs);
        // Jitter is applied before capping, not after: jittering an already-capped value can
        // scale it up to 1.2x, pushing it back over MAX_DELAY_MS and silently breaking the
        // "never wait more than 30s" guarantee. Clamping the final value keeps that a hard ceiling.
        long cappedDelayMs = Math.min(jitteredDelayMs, MAX_DELAY_MS);

        log.debug("Calculated backoff delay for attempt {}: {}ms (jittered)", attemptNumber, cappedDelayMs);
        return Duration.ofMillis(cappedDelayMs);
    }

    public boolean shouldRetry(int currentRetry, int maxRetries) {
        return currentRetry < maxRetries;
    }

    public Instant getNextRetryTime(int attemptNumber) {
        Duration delay = calculateBackoffDelay(attemptNumber);
        return Instant.now().plus(delay);
    }

    private long getJitter(long baseDelayMs) {
        double jitterFactor = JITTER_MIN + new Random().nextDouble() * JITTER_RANGE;
        return (long) (baseDelayMs * jitterFactor);
    }
}
