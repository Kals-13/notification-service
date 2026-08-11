package com.example.notificationservice.service;

import com.example.notificationservice.channel.ChannelFactory;
import com.example.notificationservice.channel.NotificationChannel;
import com.example.notificationservice.domain.AuditLog;
import com.example.notificationservice.domain.DeliveryAttempt;
import com.example.notificationservice.domain.NotificationJob;
import com.example.notificationservice.domain.NotificationJob.NotificationJobStatus;
import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.domain.Tenant;
import com.example.notificationservice.dto.NotificationJobDTO;
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
import org.springframework.data.domain.Page;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Needs the full Spring context (unlike IdempotencyKeyServiceTest): DeadLetterQueueService
 * depends on NotificationSendService, which pulls in essentially everything (repositories,
 * RateLimiterService needing Redis, etc.), so this mirrors NotificationSendServiceIntegrationTest's
 * setup rather than trying to build a lighter one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DeadLetterQueueServiceTest {

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

    @Autowired
    private DeadLetterQueueService deadLetterQueueService;
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
    @DisplayName("A FAILED job can be moved to the DLQ")
    void testMoveToDeadLetterQueue_Success() {
        Tenant tenant = createTenant("MoveTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        NotificationJob job = createJob(tenant.getId(), template.getId(), NotificationJobStatus.FAILED);

        NotificationJob updated = deadLetterQueueService.moveToDeadLetterQueue(job.getId(), "Max retries exceeded");

        assertThat(updated.getStatus()).isEqualTo(NotificationJobStatus.DEAD_LETTERED);

        List<AuditLog> logs = auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(
                tenant.getId(), "NOTIFICATION_DEAD_LETTERED");
        assertThat(logs).hasSize(1);
    }

    @Test
    @DisplayName("A non-FAILED job cannot be moved to the DLQ")
    void testMoveToDeadLetterQueue_NotFailed() {
        Tenant tenant = createTenant("NotFailedTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        NotificationJob job = createJob(tenant.getId(), template.getId(), NotificationJobStatus.DELIVERED);

        assertThatThrownBy(() -> deadLetterQueueService.moveToDeadLetterQueue(job.getId(), "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("listDeadLettered returns all dead-lettered jobs for a tenant, paginated")
    void testListDeadLettered() {
        Tenant tenant = createTenant("ListTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        for (int i = 0; i < 3; i++) {
            createJob(tenant.getId(), template.getId(), NotificationJobStatus.DEAD_LETTERED);
        }

        Page<NotificationJob> page = deadLetterQueueService.listDeadLettered(tenant.getId(), 20, 0);

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent()).allMatch(j -> j.getStatus() == NotificationJobStatus.DEAD_LETTERED);
    }

    @Test
    @DisplayName("Retrying from the DLQ resets and redispatches the same job")
    void testRetryFromDLQ_Success() {
        Tenant tenant = createTenant("RetryDlqTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        NotificationJob job = createJob(tenant.getId(), template.getId(), NotificationJobStatus.DEAD_LETTERED);
        job.setCurrentRetry(5);
        job.setMaxRetries(5);
        notificationJobRepository.save(job);
        mockAllChannelsSucceed();

        NotificationJobDTO result = deadLetterQueueService.retryFromDLQ(job.getId(), tenant.getId());

        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.currentRetry()).isZero();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationJobRepository.findById(job.getId()).orElseThrow().getStatus())
                        .isEqualTo(NotificationJobStatus.DELIVERED));

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByJobId(job.getId());
        assertThat(attempts).hasSize(1);
    }

    @Test
    @DisplayName("A non-DEAD_LETTERED job cannot be retried from the DLQ")
    void testRetryFromDLQ_NotDeadLettered() {
        Tenant tenant = createTenant("NotDlqTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        NotificationJob job = createJob(tenant.getId(), template.getId(), NotificationJobStatus.DELIVERED);

        assertThatThrownBy(() -> deadLetterQueueService.retryFromDLQ(job.getId(), tenant.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Permanently deleting a DLQ job removes it and audits the deletion")
    void testPermanentlyDeleteFromDLQ() {
        Tenant tenant = createTenant("DeleteDlqTenant");
        NotificationTemplate template = createTemplate(tenant.getId(), List.of("EMAIL"));
        NotificationJob job = createJob(tenant.getId(), template.getId(), NotificationJobStatus.DEAD_LETTERED);

        deadLetterQueueService.permanentlyDeleteFromDLQ(job.getId(), tenant.getId());

        assertThat(notificationJobRepository.findById(job.getId())).isEmpty();

        List<AuditLog> logs = auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(
                tenant.getId(), "NOTIFICATION_DELETED_FROM_DLQ");
        assertThat(logs).hasSize(1);
    }
}
