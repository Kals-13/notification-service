package com.example.notificationservice.service;

import com.example.notificationservice.exception.DuplicateIdempotencyKeyException;
import com.example.notificationservice.exception.ValidationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Uses a real Testcontainers Redis rather than a mocked RedisTemplate for most tests — the
 * whole point of this class is real Redis behavior (TTL expiry, SETNX atomicity), which a mock
 * would just have to reimplement (and could hide the same kind of bug a fake once did in
 * RateLimiterServiceTest). No Spring context is booted (no {@code @SpringBootTest}):
 * IdempotencyKeyService has exactly one dependency, so there's nothing a full context gains
 * here that a directly-constructed RedisTemplate bound to the container doesn't already give,
 * and skipping it means this test doesn't need Postgres at all.
 *
 * <p>Two adaptations from the literal spec, both because the described scenario doesn't map
 * onto the class's actual public API:
 * <ul>
 *   <li>Test 2 (expiration) can't ask {@code storeKey()} for a 1-second TTL — that method
 *       always uses the fixed 24h TTL. It writes directly to Redis at the same key format
 *       instead, to test the read path's expiry handling without changing storeKey()'s
 *       contract.</li>
 *   <li>Test 6 (duplicate detection) calls {@code validateAndStore()}, not {@code storeKey()}.
 *       storeKey() is a plain unconditional SET by design (last write wins, documented as such);
 *       {@code validateAndStore()} is the method that actually claims a key atomically and
 *       throws {@link DuplicateIdempotencyKeyException} on a clash.</li>
 * </ul>
 */
@Testcontainers
class IdempotencyKeyServiceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, String> redisTemplate;

    private IdempotencyKeyService idempotencyKeyService;

    @BeforeAll
    static void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        redisTemplate = template;
    }

    @AfterAll
    static void tearDownRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        idempotencyKeyService = new IdempotencyKeyService(redisTemplate);
    }

    @Test
    @DisplayName("A stored key can be retrieved back with the same jobId")
    void testStoreAndRetrieveIdempotencyKey() {
        UUID jobId = UUID.randomUUID();

        idempotencyKeyService.storeKey("order-123", jobId);
        Optional<UUID> result = idempotencyKeyService.getJobIdIfExists("order-123");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(jobId);
    }

    @Test
    @DisplayName("A key expires after its TTL and is no longer found")
    void testIdempotencyKeyExpiration() {
        UUID jobId = UUID.randomUUID();
        // storeKey() always uses a fixed 24h TTL; write directly at the same key format to
        // exercise the read path against a short-lived key instead.
        redisTemplate.opsForValue().set("idempotency:order-expiring", jobId.toString(), Duration.ofSeconds(1));

        assertThat(idempotencyKeyService.getJobIdIfExists("order-expiring")).isPresent();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(idempotencyKeyService.getJobIdIfExists("order-expiring")).isEmpty());
    }

    @Test
    @DisplayName("A well-formed key passes validation")
    void testValidateIdempotencyKey_Valid() {
        assertThat(idempotencyKeyService.validateIdempotencyKey("order-123")).isTrue();
    }

    @Test
    @DisplayName("An empty key fails validation")
    void testValidateIdempotencyKey_Invalid() {
        assertThatThrownBy(() -> idempotencyKeyService.validateIdempotencyKey(""))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("A key over 100 characters fails validation")
    void testValidateIdempotencyKey_TooLong() {
        String tooLong = "x".repeat(101);

        assertThatThrownBy(() -> idempotencyKeyService.validateIdempotencyKey(tooLong))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Claiming an already-claimed key for a different job throws")
    void testDuplicateKeyDetection() {
        UUID jobId1 = UUID.randomUUID();
        UUID jobId2 = UUID.randomUUID();

        assertThat(idempotencyKeyService.validateAndStore("order-123", jobId1)).isTrue();

        assertThatThrownBy(() -> idempotencyKeyService.validateAndStore("order-123", jobId2))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining("order-123")
                .hasMessageContaining(jobId1.toString());
    }

    @Test
    @DisplayName("Redis being unavailable fails open rather than throwing")
    void testRedisDownFailOpen() {
        RedisTemplate<String, String> failingTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> failingValueOps = mock(ValueOperations.class);
        when(failingTemplate.opsForValue()).thenReturn(failingValueOps);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(failingValueOps).set(anyString(), anyString(), any(Duration.class));

        IdempotencyKeyService serviceWithBrokenRedis = new IdempotencyKeyService(failingTemplate);

        serviceWithBrokenRedis.storeKey("order-123", UUID.randomUUID());
        // No exception means it failed open, as asserted.
    }
}
