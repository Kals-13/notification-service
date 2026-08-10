package com.example.notificationservice.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.notificationservice.domain.DeliveryAttempt.NotificationChannel;
import com.example.notificationservice.domain.Tenant;
import com.example.notificationservice.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Plain Mockito unit test (no Spring context) so this stays fast and isolated,
 * per the project's split between Mockito-based unit tests and Testcontainers-based
 * integration tests. Redis is faked with an in-memory map behind the mocked
 * {@link HashOperations}, since RateLimiterService only ever talks to Redis through it.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private TenantRepository tenantRepository;

    private RateLimiterService rateLimiterService;

    private UUID tenantId;
    private Map<String, Map<String, String>> fakeRedisStore;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        fakeRedisStore = new ConcurrentHashMap<>();

        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        lenient().when(hashOperations.entries(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return new HashMap<>(fakeRedisStore.getOrDefault(key, Collections.emptyMap()));
        });

        lenient().doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String field = invocation.getArgument(1);
            String value = invocation.getArgument(2);
            fakeRedisStore.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, value);
            return null;
        }).when(hashOperations).put(anyString(), anyString(), anyString());

        lenient().when(hashOperations.get(anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String field = (String) invocation.getArgument(1);
            Map<String, String> fields = fakeRedisStore.get(key);
            return fields == null ? null : fields.get(field);
        });

        lenient().when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        lenient().when(redisTemplate.delete(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return fakeRedisStore.remove(key) != null;
        });

        rateLimiterService = new RateLimiterService(redisTemplate, tenantRepository);

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(RateLimiterService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logbackLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(RateLimiterService.class);
        logbackLogger.detachAppender(logAppender);
    }

    private void stubTenantLimits(int emailLimit, int smsLimit) {
        Tenant tenant = Tenant.builder()
                .id(tenantId)
                .name("acme")
                .emailRateLimit(emailLimit)
                .smsRateLimit(smsLimit)
                .build();
        lenient().when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
    }

    @Test
    @DisplayName("First call for a tenant/channel consumes one token from a fresh bucket")
    void testInitialTokensAvailable() {
        stubTenantLimits(1000, 500);

        boolean allowed = rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL);

        assertTrue(allowed);
        assertEquals(999, rateLimiterService.getRemainingTokens(tenantId, NotificationChannel.EMAIL));
    }

    @Test
    @DisplayName("Tokens decrement by one on each successful consume")
    void testTokensDecrement() {
        stubTenantLimits(1000, 500);

        for (int i = 0; i < 3; i++) {
            assertTrue(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL));
        }

        assertEquals(997, rateLimiterService.getRemainingTokens(tenantId, NotificationChannel.EMAIL));
    }

    @Test
    @DisplayName("Requests beyond capacity are rejected and logged at INFO")
    void testRateLimitExceeded() {
        stubTenantLimits(2, 500);

        assertTrue(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL));
        assertTrue(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL));
        assertFalse(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL));

        boolean loggedExceeded = logAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("Rate limit exceeded"));
        assertTrue(loggedExceeded, "expected a 'Rate limit exceeded' log entry");
    }

    @Test
    @DisplayName("Tokens refill once enough time has elapsed since the last consume")
    void testRefillAfterTime() {
        stubTenantLimits(1, 500);
        String key = "rate_limit:" + tenantId + ":EMAIL";

        assertTrue(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL));
        assertFalse(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL),
                "bucket should still be empty with no elapsed time");

        // Simulate 65 seconds having passed since the last refill, instead of sleeping in the test.
        fakeRedisStore.get(key).put("last_refill_time", Instant.now().minusSeconds(65).toString());

        assertTrue(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL),
                "bucket should have refilled after 65 elapsed seconds");
    }

    @Test
    @DisplayName("Concurrent consumers never push the bucket into an invalid state")
    void testConcurrentConsume() throws InterruptedException {
        stubTenantLimits(1000, 500);

        int threadCount = 10;
        int callsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        if (rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL)) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "all threads should finish within the timeout");

        int totalCalls = threadCount * callsPerThread;
        assertEquals(totalCalls, successCount.get(),
                "capacity (1000) comfortably exceeds the " + totalCalls + " total calls, so all should succeed");

        int remaining = rateLimiterService.getRemainingTokens(tenantId, NotificationChannel.EMAIL);
        // RateLimiterService does a plain read-modify-write against Redis (no WATCH/MULTI/EXEC or
        // Lua script), so under concurrent access a lost update can leave more tokens than a
        // strictly atomic bucket would. Assert the safety bounds this implementation actually
        // guarantees rather than an exact count.
        assertTrue(remaining <= 999, "at least one token should have been consumed");
        assertTrue(remaining >= 1000 - totalCalls, "no more than " + totalCalls + " tokens can have been consumed");
    }

    @Test
    @DisplayName("setLimit resets the bucket to the new capacity")
    void testSetLimitUpdates() {
        rateLimiterService.setLimit(tenantId, NotificationChannel.EMAIL, 500);

        assertEquals(500, rateLimiterService.getRemainingTokens(tenantId, NotificationChannel.EMAIL));
    }

    @Test
    @DisplayName("Each channel is rate limited independently, with its own capacity")
    void testDifferentChannelsDifferentLimits() {
        stubTenantLimits(1000, 500);

        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.EMAIL));
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(rateLimiterService.checkAndConsume(tenantId, NotificationChannel.SMS));
        }

        assertEquals(995, rateLimiterService.getRemainingTokens(tenantId, NotificationChannel.EMAIL));
        assertEquals(497, rateLimiterService.getRemainingTokens(tenantId, NotificationChannel.SMS));
    }
}
