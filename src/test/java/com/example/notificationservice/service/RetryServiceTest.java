package com.example.notificationservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetryService has no dependencies (no Redis, no repositories), so it is
 * instantiated directly rather than bootstrapping a Spring context.
 */
class RetryServiceTest {

    private RetryService retryService;

    @BeforeEach
    void setUp() {
        retryService = new RetryService();
    }

    @Test
    @DisplayName("Attempt 1 backoff is 2000ms +/- 20% jitter")
    void testBackoffDelay_Attempt1() {
        Duration delay = retryService.calculateBackoffDelay(1);

        assertThat(delay.toMillis()).isBetween(1600L, 2400L);
    }

    @Test
    @DisplayName("Attempt 2 backoff is 4000ms +/- 20% jitter")
    void testBackoffDelay_Attempt2() {
        Duration delay = retryService.calculateBackoffDelay(2);

        assertThat(delay.toMillis()).isBetween(3200L, 4800L);
    }

    @Test
    @DisplayName("Backoff delay is capped at 30 seconds")
    void testBackoffDelay_Capped() {
        Duration delay = retryService.calculateBackoffDelay(5);

        assertTrue(delay.toMillis() <= 30000L);
    }

    @Test
    @DisplayName("shouldRetry returns true while currentRetry is below maxRetries")
    void testShouldRetry_WithinMaxRetries() {
        assertTrue(retryService.shouldRetry(2, 5));
    }

    @Test
    @DisplayName("shouldRetry returns false once currentRetry reaches maxRetries")
    void testShouldRetry_ExceedsMaxRetries() {
        assertFalse(retryService.shouldRetry(5, 5));
    }

    @Test
    @DisplayName("getNextRetryTime returns a future Instant offset by the backoff delay")
    void testGetNextRetryTime() {
        Instant before = Instant.now();

        Instant nextRetryTime = retryService.getNextRetryTime(1);

        assertTrue(nextRetryTime.isAfter(before));
        long delayMillis = Duration.between(before, nextRetryTime).toMillis();
        assertTrue(delayMillis > 0, "delay should not be immediate");
        assertThat(delayMillis).isBetween(1600L, 2400L);
    }

    @Test
    @DisplayName("Jitter distributes delays within the +/-20% band and averages near the base delay")
    void testJitterVariance() {
        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            delays.add(retryService.calculateBackoffDelay(1).toMillis());
        }

        long min = delays.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = delays.stream().mapToLong(Long::longValue).max().orElseThrow();
        double average = delays.stream().mapToLong(Long::longValue).average().orElseThrow();

        assertTrue(min >= 1600L, "min delay should not fall below the 80% jitter floor");
        assertTrue(max <= 2400L, "max delay should not exceed the 120% jitter ceiling");

        double baseDelayMs = 2000.0;
        assertThat(average).isCloseTo(baseDelayMs, org.assertj.core.data.Percentage.withPercentage(10));
    }
}
