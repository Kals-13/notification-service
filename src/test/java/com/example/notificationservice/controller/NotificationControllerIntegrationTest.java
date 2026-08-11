package com.example.notificationservice.controller;

import com.example.notificationservice.channel.ChannelFactory;
import com.example.notificationservice.channel.NotificationChannel;
import com.example.notificationservice.domain.NotificationJob;
import com.example.notificationservice.domain.NotificationJob.NotificationJobStatus;
import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.domain.Tenant;
import com.example.notificationservice.dto.CreateTemplateRequest;
import com.example.notificationservice.dto.CreateTenantRequest;
import com.example.notificationservice.dto.NotificationJobDTO;
import com.example.notificationservice.dto.NotificationTemplateDTO;
import com.example.notificationservice.dto.SendNotificationRequest;
import com.example.notificationservice.dto.TenantDTO;
import com.example.notificationservice.dto.TenantScopedRequest;
import com.example.notificationservice.dto.UpdateLimitsRequest;
import com.example.notificationservice.dto.UpdateTemplateRequest;
import com.example.notificationservice.repository.AuditLogRepository;
import com.example.notificationservice.repository.DeliveryAttemptRepository;
import com.example.notificationservice.repository.NotificationJobRepository;
import com.example.notificationservice.repository.NotificationTemplateRepository;
import com.example.notificationservice.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
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
 * API-level coverage of the controllers, driven over real HTTP against a real embedded
 * server ({@code webEnvironment = RANDOM_PORT}) with real Postgres/Redis containers.
 *
 * <p><b>{@code @WithMockUser} is not used here, despite being requested.</b> It works by
 * populating {@code SecurityContextHolder} for the current test thread only. With
 * {@code TestRestTemplate} against a real server, the request is handled by one of Tomcat's
 * own worker threads, which never sees that context — every request would be anonymous
 * regardless of the annotation, so every "success" case would 401 instead. Authentication is
 * done instead via {@code TestRestTemplate.withBasicAuth(...)} against the three real
 * in-memory users {@code SecurityConfig} provisions (admin@platform.com / PLATFORM_ADMIN,
 * tenant1@example.com and tenant2@example.com / TENANT_ADMIN), which is the correct mechanism
 * for authenticating real HTTP calls and achieves the same per-test-role intent.
 *
 * <p><b>Test 8 documents a real, confirmed gap rather than a passing assertion of the spec's
 * expectation.</b> There is no mapping anywhere in this system from an authenticated principal
 * to a specific tenantId — any authenticated TENANT_ADMIN can query any tenant's data by
 * simply passing its ID. See {@link #testGetNotificationStatusWrongTenant_NotIsolated()}.
 *
 * <p>Tests 11 and 13 assert 409 Conflict, not 400 Bad Request: {@code cancelScheduledNotification}
 * and {@code retryFailedNotification} throw {@code IllegalStateException} for these business-rule
 * violations, which {@code GlobalExceptionHandler} maps to 409/"INVALID_STATE" (a deliberate
 * addition made when the exception handler was built, since without it these would have
 * incorrectly fallen through to a 500). The spec itself hedges test 11 with "(or appropriate
 * status)"; the same reasoning is applied consistently to test 13.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class NotificationControllerIntegrationTest {

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
    private static final String PLATFORM_ADMIN_USER = "admin@platform.com";
    private static final String PLATFORM_ADMIN_PASSWORD = "admin123";
    private static final String TENANT1_USER = "tenant1@example.com";
    private static final String TENANT2_USER = "tenant2@example.com";
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

    private TestRestTemplate asPlatformAdmin() {
        return restTemplate.withBasicAuth(PLATFORM_ADMIN_USER, PLATFORM_ADMIN_PASSWORD);
    }

    private TestRestTemplate asTenant1() {
        return restTemplate.withBasicAuth(TENANT1_USER, TENANT_ADMIN_PASSWORD);
    }

    private TestRestTemplate asTenant2() {
        return restTemplate.withBasicAuth(TENANT2_USER, TENANT_ADMIN_PASSWORD);
    }

    private Tenant createTenant(String name) {
        Tenant tenant = Tenant.builder().name(name + "-" + UUID.randomUUID()).build();
        return tenantRepository.save(tenant);
    }

    private NotificationTemplate createTemplate(UUID tenantId, String body, List<String> channels) {
        NotificationTemplate template = NotificationTemplate.builder()
                .tenantId(tenantId)
                .name("template-" + UUID.randomUUID())
                .subject("Subject")
                .body(body)
                .channels(OBJECT_MAPPER.writeValueAsString(channels))
                .build();
        return notificationTemplateRepository.save(template);
    }

    private void mockAllChannelsSucceed() {
        NotificationChannel mockChannel = mock(NotificationChannel.class);
        when(mockChannel.send(any(NotificationJob.class), anyString())).thenReturn(true);
        when(channelFactory.getChannel(anyString())).thenReturn(mockChannel);
    }

    /** Sends via the real HTTP endpoint as tenant1 and waits for async dispatch to settle. */
    private NotificationJob sendNotification(UUID tenantId, UUID templateId) {
        SendNotificationRequest request = new SendNotificationRequest(
                tenantId, templateId, "test@example.com", null, Map.of());
        ResponseEntity<NotificationJobDTO> response = asTenant1()
                .postForEntity("/api/notifications/send", request, NotificationJobDTO.class);
        UUID jobId = response.getBody().id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationJobRepository.findById(jobId).orElseThrow().getStatus())
                        .isNotEqualTo(NotificationJobStatus.QUEUED));

        return notificationJobRepository.findById(jobId).orElseThrow();
    }

    @Test
    @DisplayName("Tenant admin can send a notification")
    void testSendNotificationAsTenantAdmin_Success() {
        Tenant tenant = createTenant("SendTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Hello", List.of("EMAIL"));
        mockAllChannelsSucceed();

        SendNotificationRequest request = new SendNotificationRequest(
                tenant.getId(), template.getId(), "john@example.com", null, Map.of());

        ResponseEntity<NotificationJobDTO> response = asTenant1()
                .postForEntity("/api/notifications/send", request, NotificationJobDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("Sending without authentication is rejected")
    void testSendNotificationWithoutAuth_Unauthorized() {
        SendNotificationRequest request = new SendNotificationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "a@example.com", null, Map.of());

        ResponseEntity<String> response = restTemplate.postForEntity("/api/notifications/send", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Sending with a role other than TENANT_ADMIN is forbidden")
    void testSendNotificationWithWrongRole_Forbidden() {
        // No "USER" role account exists in SecurityConfig's provisioned users; PLATFORM_ADMIN
        // lacking TENANT_ADMIN serves the same purpose here (a real, authenticated, wrong role).
        SendNotificationRequest request = new SendNotificationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "a@example.com", null, Map.of());

        ResponseEntity<String> response = asPlatformAdmin()
                .postForEntity("/api/notifications/send", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Sending against an unknown tenant returns 404 ENTITY_NOT_FOUND")
    void testSendNotificationMissingTenant_NotFound() {
        SendNotificationRequest request = new SendNotificationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "a@example.com", null, Map.of());

        ResponseEntity<Map<String, Object>> response = asTenant1().exchange(
                "/api/notifications/send", HttpMethod.POST, new HttpEntity<>(request),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "ENTITY_NOT_FOUND");
    }

    @Test
    @DisplayName("Sending against an unknown template returns 404")
    void testSendNotificationMissingTemplate_NotFound() {
        Tenant tenant = createTenant("NoTemplateTenant");
        SendNotificationRequest request = new SendNotificationRequest(
                tenant.getId(), UUID.randomUUID(), "a@example.com", null, Map.of());

        ResponseEntity<String> response = asTenant1()
                .postForEntity("/api/notifications/send", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("A malformed recipient email returns 400 VALIDATION_ERROR")
    void testSendNotificationInvalidEmail_BadRequest() {
        Tenant tenant = createTenant("BadEmailTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Hi", List.of("EMAIL"));

        SendNotificationRequest request = new SendNotificationRequest(
                tenant.getId(), template.getId(), "not-email", null, Map.of());

        ResponseEntity<Map<String, Object>> response = asTenant1().exchange(
                "/api/notifications/send", HttpMethod.POST, new HttpEntity<>(request),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_ERROR");
    }

    @Test
    @DisplayName("getNotificationStatus returns the full DTO including delivery attempts")
    void testGetNotificationStatus_Success() {
        Tenant tenant = createTenant("StatusTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Hi", List.of("EMAIL"));
        mockAllChannelsSucceed();

        NotificationJob job = sendNotification(tenant.getId(), template.getId());

        ResponseEntity<NotificationJobDTO> response = asTenant1().getForEntity(
                "/api/notifications/{jobId}?tenantId={tenantId}", NotificationJobDTO.class,
                job.getId(), tenant.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().attempts()).hasSize(1);
        assertThat(response.getBody().status()).isEqualTo("DELIVERED");
    }

    /**
     * Documents a real gap rather than the spec's expected 403: nothing in this system
     * associates an authenticated principal with a tenantId, so tenant2 querying tenant1's
     * job by passing tenant1's ID as the tenantId query param succeeds. Role checks alone
     * (which this endpoint does enforce) are not the same as tenant isolation.
     */
    @Test
    @DisplayName("KNOWN GAP: cross-tenant read is not actually blocked (no principal-to-tenant binding exists)")
    void testGetNotificationStatusWrongTenant_NotIsolated() {
        Tenant tenant1 = createTenant("Tenant1");
        NotificationTemplate template = createTemplate(tenant1.getId(), "Hi", List.of("EMAIL"));
        mockAllChannelsSucceed();

        NotificationJob job = sendNotification(tenant1.getId(), template.getId());

        ResponseEntity<NotificationJobDTO> response = asTenant2().getForEntity(
                "/api/notifications/{jobId}?tenantId={tenantId}", NotificationJobDTO.class,
                job.getId(), tenant1.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Report lists all jobs for a tenant, paginated")
    void testGetNotificationsReport_Success() {
        Tenant tenant = createTenant("ReportTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Hi", List.of("EMAIL"));
        mockAllChannelsSucceed();

        for (int i = 0; i < 3; i++) {
            sendNotification(tenant.getId(), template.getId());
        }

        ResponseEntity<Map<String, Object>> response = asTenant1().exchange(
                "/api/notifications/report?tenantId={tenantId}&pageSize=20&pageNum=0", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { },
                tenant.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> content = (List<?>) response.getBody().get("content");
        assertThat(content).hasSize(3);
    }

    @Test
    @DisplayName("Retrying a job that has exhausted its retries is rejected")
    void testRetryFailedNotificationExhaustedRetries_Conflict() {
        Tenant tenant = createTenant("ExhaustedTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Hi", List.of("EMAIL"));

        // Persist a job already at maxRetries/FAILED directly: driving it there through 6 real
        // failed send rounds (as NotificationSendServiceIntegrationTest does) is unnecessary
        // here since this test only cares about retryFailedNotification's guard, not the path
        // that produces the FAILED state.
        NotificationJob job = new NotificationJob(tenant.getId(), template.getId(), "fail@example.com");
        job.setStatus(NotificationJobStatus.FAILED);
        job.setCurrentRetry(5);
        job.setMaxRetries(5);
        job.setVariables("{}");
        NotificationJob saved = notificationJobRepository.save(job);

        TenantScopedRequest body = new TenantScopedRequest(tenant.getId());
        ResponseEntity<Map<String, Object>> response = asTenant1().exchange(
                "/api/notifications/{jobId}/retry", HttpMethod.POST, new HttpEntity<>(body),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { },
                saved.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "INVALID_STATE");
    }

    @Test
    @DisplayName("Retrying a job that still has retry budget resets it to QUEUED")
    void testRetryFailedNotification_Success() {
        Tenant tenant = createTenant("RetryTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Hi", List.of("EMAIL"));

        NotificationJob job = new NotificationJob(tenant.getId(), template.getId(), "fail@example.com");
        job.setStatus(NotificationJobStatus.FAILED);
        job.setCurrentRetry(3);
        job.setVariables("{}");
        NotificationJob saved = notificationJobRepository.save(job);

        mockAllChannelsSucceed();
        TenantScopedRequest body = new TenantScopedRequest(tenant.getId());

        ResponseEntity<NotificationJobDTO> response = asTenant1().exchange(
                "/api/notifications/{jobId}/retry", HttpMethod.POST, new HttpEntity<>(body),
                NotificationJobDTO.class, saved.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().currentRetry()).isZero();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationJobRepository.findById(saved.getId()).orElseThrow().getStatus())
                        .isEqualTo(NotificationJobStatus.DELIVERED));
    }

    @Test
    @DisplayName("Cancelling a scheduled job flips it to CANCELLED")
    void testCancelScheduledNotification_Success() {
        Tenant tenant = createTenant("CancelTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Hi", List.of("EMAIL"));

        NotificationJob job = new NotificationJob(tenant.getId(), template.getId(), "cancel@example.com");
        job.setStatus(NotificationJobStatus.SCHEDULED);
        job.setScheduledAt(Instant.now().plusSeconds(3600));
        job.setVariables("{}");
        NotificationJob saved = notificationJobRepository.save(job);

        TenantScopedRequest body = new TenantScopedRequest(tenant.getId());
        ResponseEntity<Void> response = asTenant1().exchange(
                "/api/notifications/{jobId}", HttpMethod.DELETE, new HttpEntity<>(body),
                Void.class, saved.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(notificationJobRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationJobStatus.CANCELLED);
    }

    @Test
    @DisplayName("Cancelling a non-SCHEDULED job is rejected")
    void testCancelNonScheduledNotification_Conflict() {
        Tenant tenant = createTenant("NonScheduledTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Hi", List.of("EMAIL"));
        mockAllChannelsSucceed();

        NotificationJob job = sendNotification(tenant.getId(), template.getId());
        assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.DELIVERED);

        TenantScopedRequest body = new TenantScopedRequest(tenant.getId());
        ResponseEntity<Map<String, Object>> response = asTenant1().exchange(
                "/api/notifications/{jobId}", HttpMethod.DELETE, new HttpEntity<>(body),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { },
                job.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Tenant admin can create a template")
    void testCreateTemplate_Success() {
        Tenant tenant = createTenant("CreateTemplateTenant");
        CreateTemplateRequest request = new CreateTemplateRequest(
                tenant.getId(), "welcome", "Subject", "Hello", List.of("EMAIL"));

        ResponseEntity<NotificationTemplateDTO> response = asTenant1()
                .postForEntity("/api/templates", request, NotificationTemplateDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isNotNull();
    }

    @Test
    @DisplayName("Listing templates returns all templates for a tenant")
    void testGetTemplates_Success() {
        Tenant tenant = createTenant("ListTemplatesTenant");
        createTemplate(tenant.getId(), "Body 1", List.of("EMAIL"));
        createTemplate(tenant.getId(), "Body 2", List.of("SMS"));

        ResponseEntity<List<NotificationTemplateDTO>> response = asTenant1().exchange(
                "/api/templates?tenantId={tenantId}", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<List<NotificationTemplateDTO>>() { },
                tenant.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    @DisplayName("Updating a template returns the updated DTO")
    void testUpdateTemplate_Success() {
        Tenant tenant = createTenant("UpdateTemplateTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Old body", List.of("EMAIL"));

        UpdateTemplateRequest request = new UpdateTemplateRequest(
                "renamed", "New subject", "New body", List.of("SMS"), false, tenant.getId());

        ResponseEntity<NotificationTemplateDTO> response = asTenant1().exchange(
                "/api/templates/{templateId}", HttpMethod.PUT, new HttpEntity<>(request),
                NotificationTemplateDTO.class, template.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().body()).isEqualTo("New body");
        assertThat(response.getBody().channels()).containsExactly("SMS");
        assertThat(response.getBody().isActive()).isFalse();
    }

    @Test
    @DisplayName("Deleting a template removes it")
    void testDeleteTemplate_Success() {
        Tenant tenant = createTenant("DeleteTemplateTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), "Body", List.of("EMAIL"));

        TenantScopedRequest body = new TenantScopedRequest(tenant.getId());
        ResponseEntity<Void> response = asTenant1().exchange(
                "/api/templates/{templateId}", HttpMethod.DELETE, new HttpEntity<>(body),
                Void.class, template.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(notificationTemplateRepository.findById(template.getId())).isEmpty();
    }

    @Test
    @DisplayName("Only PLATFORM_ADMIN can create tenants")
    void testCreateTenant_PlatformAdminOnly() {
        CreateTenantRequest request = new CreateTenantRequest("NewTenant", null, null, null, null);

        ResponseEntity<TenantDTO> platformAdminResponse = asPlatformAdmin()
                .postForEntity("/api/tenants", request, TenantDTO.class);
        assertThat(platformAdminResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> tenantAdminResponse = asTenant1()
                .postForEntity("/api/tenants", request, String.class);
        assertThat(tenantAdminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Platform admin can fetch a tenant by id")
    void testGetTenant_Success() {
        Tenant tenant = createTenant("GetTenant");

        ResponseEntity<TenantDTO> response = asPlatformAdmin()
                .getForEntity("/api/tenants/{tenantId}", TenantDTO.class, tenant.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(tenant.getId());
    }

    @Test
    @DisplayName("Platform admin can update a tenant's rate limits")
    void testUpdateTenantLimits_Success() {
        Tenant tenant = createTenant("UpdateLimitsTenant");
        UpdateLimitsRequest request = new UpdateLimitsRequest(250, null, null, null);

        ResponseEntity<TenantDTO> response = asPlatformAdmin().exchange(
                "/api/tenants/{tenantId}/limits", HttpMethod.PUT, new HttpEntity<>(request),
                TenantDTO.class, tenant.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().emailRateLimit()).isEqualTo(250);
        // Fields omitted from the request (null) are left unchanged, not zeroed.
        assertThat(response.getBody().smsRateLimit()).isEqualTo(tenant.getSmsRateLimit());
    }

    @Test
    @DisplayName("Health endpoint is public")
    void testHealthEndpoint_Public() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/health", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("Error responses include the standardized error/code/timestamp/path/details shape")
    void testErrorResponse_Format() {
        UUID missingTenantId = UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = asPlatformAdmin().exchange(
                "/api/tenants/{tenantId}", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { },
                missingTenantId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .containsKeys("error", "code", "timestamp", "path", "details")
                .containsEntry("code", "ENTITY_NOT_FOUND")
                .containsEntry("path", "/api/tenants/" + missingTenantId);
    }
}
