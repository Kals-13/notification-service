package com.example.notificationservice.controller;

import com.example.notificationservice.channel.ChannelFactory;
import com.example.notificationservice.channel.NotificationChannel;
import com.example.notificationservice.domain.NotificationJob;
import com.example.notificationservice.domain.NotificationJob.NotificationJobStatus;
import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.domain.Tenant;
import com.example.notificationservice.dto.NotificationJobDTO;
import com.example.notificationservice.dto.SendNotificationRequest;
import com.example.notificationservice.dto.TenantScopedRequest;
import com.example.notificationservice.repository.AuditLogRepository;
import com.example.notificationservice.repository.DeliveryAttemptRepository;
import com.example.notificationservice.repository.NotificationJobRepository;
import com.example.notificationservice.repository.NotificationTemplateRepository;
import com.example.notificationservice.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mirrors NotificationControllerIntegrationTest's setup and reasoning: no {@code @WithMockUser}
 * (incompatible with a real HTTP client against a real server — see that class's Javadoc for
 * why), authentication via {@code TestRestTemplate.withBasicAuth(...)} against SecurityConfig's
 * real in-memory users instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DeadLetterQueueControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("notification_db")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TENANT1_USER = "tenant1@example.com";
    private static final String TENANT_ADMIN_PASSWORD = "tenant123";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private NotificationJobRepository notificationJobRepository;
    @Autowired
    private NotificationTemplateRepository notificationTemplateRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @MockitoBean
    private ChannelFactory channelFactory;

    @AfterEach
    void cleanUp() {
        deliveryAttemptRepository.deleteAll();
        auditLogRepository.deleteAll();
        notificationJobRepository.deleteAll();
        notificationTemplateRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    private TestRestTemplate asTenant1() {
        return restTemplate.withBasicAuth(TENANT1_USER, TENANT_ADMIN_PASSWORD);
    }

    private Tenant createTenant(String name) {
        Tenant tenant = Tenant.builder().name(name + "-" + UUID.randomUUID()).build();
        return tenantRepository.save(tenant);
    }

    private NotificationTemplate createTemplate(UUID tenantId, List<String> channels) {
        NotificationTemplate template = NotificationTemplate.builder()
                .tenantId(tenantId)
                .name("template-" + UUID.randomUUID())
                .subject("Subject")
                .body("Hi")
                .channels(OBJECT_MAPPER.writeValueAsString(channels))
                .build();
        return notificationTemplateRepository.save(template);
    }

    private NotificationJob createJob(UUID tenantId, UUID templateId, NotificationJobStatus status) {
        NotificationJob job = new NotificationJob(tenantId, templateId, "test@example.com");
        job.setStatus(status);
        job.setVariables("{}");
        return notificationJobRepository.save(job);
    }

    private void mockAllChannelsSucceed() {
        NotificationChannel mockChannel = mock(NotificationChannel.class);
        when(mockChannel.send(any(NotificationJob.class), anyString())).thenReturn(true);
        when(channelFactory.getChannel(anyString())).thenReturn(mockChannel);
    }

    @Test
    @DisplayName("Listing the DLQ returns all dead-lettered jobs for a tenant")
    void testListDeadLettered_Success() {
        Tenant tenant = createTenant("ListDlqTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        for (int i = 0; i < 3; i++) {
            createJob(tenant.getId(), template.getId(), NotificationJobStatus.DEAD_LETTERED);
        }

        ResponseEntity<Map<String, Object>> response = asTenant1().exchange(
                "/api/dlq?tenantId={tenantId}&pageSize=20&pageNum=0", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { },
                tenant.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) response.getBody().get("content");
        assertThat(content).hasSize(3);
    }

    @Test
    @DisplayName("Listing the DLQ without authentication is rejected")
    void testListDeadLettered_Unauthorized() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/dlq?tenantId=" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Retrying a dead-lettered job resets and redispatches it")
    void testRetryFromDLQ_Success() {
        Tenant tenant = createTenant("RetryDlqTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        NotificationJob job = createJob(tenant.getId(), template.getId(), NotificationJobStatus.DEAD_LETTERED);
        job.setCurrentRetry(5);
        job.setMaxRetries(5);
        notificationJobRepository.save(job);
        mockAllChannelsSucceed();

        TenantScopedRequest body = new TenantScopedRequest(tenant.getId());
        ResponseEntity<NotificationJobDTO> response = asTenant1().exchange(
                "/api/dlq/{jobId}/retry", HttpMethod.POST, new HttpEntity<>(body),
                NotificationJobDTO.class, job.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("QUEUED");
        assertThat(response.getBody().currentRetry()).isZero();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationJobRepository.findById(job.getId()).orElseThrow().getStatus())
                        .isEqualTo(NotificationJobStatus.DELIVERED));
    }

    @Test
    @DisplayName("Retrying a non-dead-lettered job is rejected")
    void testRetryFromDLQ_NotDeadLettered() {
        Tenant tenant = createTenant("NotDlqTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        NotificationJob job = createJob(tenant.getId(), template.getId(), NotificationJobStatus.DELIVERED);

        TenantScopedRequest body = new TenantScopedRequest(tenant.getId());
        ResponseEntity<Map<String, Object>> response = asTenant1().exchange(
                "/api/dlq/{jobId}/retry", HttpMethod.POST, new HttpEntity<>(body),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { },
                job.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Deleting from the DLQ permanently removes the job")
    void testDeleteFromDLQ_Success() {
        Tenant tenant = createTenant("DeleteDlqTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        NotificationJob job = createJob(tenant.getId(), template.getId(), NotificationJobStatus.DEAD_LETTERED);

        TenantScopedRequest body = new TenantScopedRequest(tenant.getId());
        ResponseEntity<Void> response = asTenant1().exchange(
                "/api/dlq/{jobId}", HttpMethod.DELETE, new HttpEntity<>(body),
                Void.class, job.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(notificationJobRepository.findById(job.getId())).isEmpty();
    }

    @Test
    @DisplayName("Sending twice with the same idempotency key returns the same cached job")
    void testIdempotencyKey_DuplicateRequest() {
        Tenant tenant = createTenant("IdemDupTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        mockAllChannelsSucceed();

        SendNotificationRequest request = new SendNotificationRequest(
                tenant.getId(), template.getId(), "dup@example.com", null, Map.of());
        HttpEntity<SendNotificationRequest> entityWithKey = new HttpEntity<>(request, headersWithIdempotencyKey("key-123"));

        // sendNotification() returns 200 OK on both the original send and the cached-response
        // path (there's no separate "201 Created" branch in the controller for a first send).
        ResponseEntity<NotificationJobDTO> first = asTenant1().exchange(
                "/api/notifications/send", HttpMethod.POST, entityWithKey, NotificationJobDTO.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID firstJobId = first.getBody().id();

        ResponseEntity<NotificationJobDTO> second = asTenant1().exchange(
                "/api/notifications/send", HttpMethod.POST, entityWithKey, NotificationJobDTO.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().id()).isEqualTo(firstJobId);

        assertThat(notificationJobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sending with different idempotency keys creates two distinct jobs")
    void testIdempotencyKey_DifferentKey() {
        Tenant tenant = createTenant("IdemDiffTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        mockAllChannelsSucceed();

        SendNotificationRequest request = new SendNotificationRequest(
                tenant.getId(), template.getId(), "diff@example.com", null, Map.of());

        ResponseEntity<NotificationJobDTO> first = asTenant1().exchange(
                "/api/notifications/send", HttpMethod.POST,
                new HttpEntity<>(request, headersWithIdempotencyKey("key-1")), NotificationJobDTO.class);

        ResponseEntity<NotificationJobDTO> second = asTenant1().exchange(
                "/api/notifications/send", HttpMethod.POST,
                new HttpEntity<>(request, headersWithIdempotencyKey("key-2")), NotificationJobDTO.class);

        assertThat(first.getBody().id()).isNotEqualTo(second.getBody().id());
        assertThat(notificationJobRepository.count()).isEqualTo(2);
    }

    private org.springframework.http.HttpHeaders headersWithIdempotencyKey(String key) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Idempotency-Key", key);
        return headers;
    }
}
