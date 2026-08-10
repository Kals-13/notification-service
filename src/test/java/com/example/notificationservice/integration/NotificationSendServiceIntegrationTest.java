package com.example.notificationservice.integration;

import com.example.notificationservice.channel.ChannelFactory;
import com.example.notificationservice.channel.NotificationChannel;
import com.example.notificationservice.domain.AuditLog;
import com.example.notificationservice.domain.DeliveryAttempt;
import com.example.notificationservice.domain.DeliveryAttempt.DeliveryAttemptStatus;
import com.example.notificationservice.domain.NotificationJob;
import com.example.notificationservice.domain.NotificationJob.NotificationJobStatus;
import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.domain.Tenant;
import com.example.notificationservice.dto.NotificationJobDTO;
import com.example.notificationservice.dto.SendNotificationRequest;
import com.example.notificationservice.exception.EntityNotFoundException;
import com.example.notificationservice.exception.InvalidTemplateException;
import com.example.notificationservice.exception.ValidationException;
import com.example.notificationservice.repository.AuditLogRepository;
import com.example.notificationservice.repository.DeliveryAttemptRepository;
import com.example.notificationservice.repository.NotificationJobRepository;
import com.example.notificationservice.repository.NotificationTemplateRepository;
import com.example.notificationservice.repository.TenantRepository;
import com.example.notificationservice.service.NotificationSendService;
import com.example.notificationservice.service.RateLimiterService;
import com.example.notificationservice.service.VariableSubstitutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage of NotificationSendService against real Postgres/Redis containers.
 *
 * Two deliberate deviations from a literal reading of the spec, both because the
 * feature they assume doesn't exist in NotificationSendService as built:
 * <ul>
 *   <li>{@link SendNotificationRequest} has no {@code scheduledAt} field and
 *       {@code sendNotification} always dispatches immediately. Tests that need a
 *       SCHEDULED job (cancel, retryScheduledJobs) persist one directly via the
 *       repository instead of going through sendNotification().</li>
 *   <li>{@code retryFailedNotification} rejects any job whose currentRetry has already
 *       reached maxRetries (IllegalStateException) — which is exactly the state a job
 *       is in once it's genuinely FAILED. That's tested as the real, current contract
 *       rather than asserting a reset-to-QUEUED that the code does not implement; see
 *       the note on {@link #testRetryFailedNotification()}.</li>
 * </ul>
 * {@code @MockBean} no longer exists on this Spring/Boot version (Spring Framework 7);
 * {@code @MockitoBean} is its replacement and is used throughout. Waits use Awaitility
 * polling rather than a fixed {@code Thread.sleep(500)}, since async dispatch timing
 * against real containers is not reliably bounded by a flat 500ms.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class NotificationSendServiceIntegrationTest {

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
    private NotificationSendService notificationSendService;
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
    @Autowired
    private VariableSubstitutionService variableSubstitutionService;
    @Autowired
    private RateLimiterService rateLimiterService;

    @MockitoBean
    private ChannelFactory channelFactory;

    @AfterEach
    void cleanUp() {
        // @DirtiesContext rebuilds the Spring context between tests but the containers
        // (and their data) are shared across the whole class, so tables need an explicit wipe.
        deliveryAttemptRepository.deleteAll();
        auditLogRepository.deleteAll();
        notificationJobRepository.deleteAll();
        notificationTemplateRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    private Tenant createTenant(String name, Integer emailLimit) {
        Tenant tenant = Tenant.builder()
                .name(name + "-" + UUID.randomUUID())
                .emailRateLimit(emailLimit)
                .build();
        return tenantRepository.save(tenant);
    }

    private NotificationTemplate createTemplate(UUID tenantId, String name, String body, List<String> channels) {
        NotificationTemplate template = NotificationTemplate.builder()
                .tenantId(tenantId)
                .name(name)
                .subject("Subject")
                .body(body)
                .channels(toJson(channels))
                .build();
        return notificationTemplateRepository.save(template);
    }

    private SendNotificationRequest createSendRequest(UUID tenantId, UUID templateId, String email, Map<String, String> vars) {
        return new SendNotificationRequest(tenantId, templateId, email, null, vars);
    }

    private String toJson(Object value) {
        return OBJECT_MAPPER.writeValueAsString(value);
    }

    private NotificationChannel mockAllChannelsSucceed() {
        NotificationChannel mockChannel = mock(NotificationChannel.class);
        when(mockChannel.send(any(NotificationJob.class), anyString())).thenReturn(true);
        when(channelFactory.getChannel(anyString())).thenReturn(mockChannel);
        return mockChannel;
    }

    @Test
    @DisplayName("Happy path: job is queued, dispatched, delivered, and audited")
    void testSendNotificationHappyPath() {
        Tenant tenant = createTenant("TestTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "welcome",
                "Hello ${firstName}, your order ${orderId} is ready", List.of("EMAIL"));
        NotificationChannel mockChannel = mockAllChannelsSucceed();

        SendNotificationRequest request = createSendRequest(tenant.getId(), template.getId(), "john@example.com",
                Map.of("firstName", "John", "orderId", "12345"));

        NotificationJobDTO result = notificationSendService.sendNotification(request);

        assertThat(result).isNotNull();
        assertThat(result.status()).isIn("QUEUED", "DELIVERED");

        UUID jobId = result.id();
        assertThat(notificationJobRepository.findById(jobId)).isPresent();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            NotificationJob job = notificationJobRepository.findById(jobId).orElseThrow();
            assertThat(job.getStatus()).isEqualTo(NotificationJobStatus.DELIVERED);
        });

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByJobId(jobId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(DeliveryAttemptStatus.SENT);

        List<AuditLog> sentLogs = auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(
                tenant.getId(), "NOTIFICATION_SENT");
        assertThat(sentLogs).hasSize(1);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockChannel).send(any(NotificationJob.class), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isEqualTo("Hello John, your order 12345 is ready");
    }

    @Test
    @DisplayName("All template variables are substituted into the body passed to the channel")
    void testSendNotificationWithVariableSubstitution() {
        Tenant tenant = createTenant("VarTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "order",
                "Order ${orderId} for customer ${customerName} at ${location}", List.of("EMAIL"));
        NotificationChannel mockChannel = mockAllChannelsSucceed();

        SendNotificationRequest request = createSendRequest(tenant.getId(), template.getId(), "alice@example.com",
                Map.of("orderId", "ORD-999", "customerName", "Alice", "location", "NYC"));

        NotificationJobDTO result = notificationSendService.sendNotification(request);
        UUID jobId = result.id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationJobRepository.findById(jobId).orElseThrow().getStatus())
                        .isEqualTo(NotificationJobStatus.DELIVERED));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockChannel).send(any(NotificationJob.class), bodyCaptor.capture());
        String renderedBody = bodyCaptor.getValue();

        assertThat(renderedBody).isEqualTo("Order ORD-999 for customer Alice at NYC");
        assertThat(renderedBody).doesNotContain("${");
    }

    @Test
    @DisplayName("Third send within a window is rate limited while the first two succeed")
    void testSendNotificationWithRateLimit() {
        Tenant tenant = createTenant("RateLimitTenant", 2);
        NotificationTemplate template = createTemplate(tenant.getId(), "rl", "Test ${x}", List.of("EMAIL"));
        mockAllChannelsSucceed();

        List<UUID> jobIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            NotificationJobDTO result = notificationSendService.sendNotification(
                    createSendRequest(tenant.getId(), template.getId(), "user" + i + "@example.com", Map.of("x", "1")));
            jobIds.add(result.id());
        }

        // Which of the three jobs loses the race for the 2 available tokens is not
        // deterministic (all three dispatch to the same bounded pool near-simultaneously),
        // so the assertions below are on aggregate counts rather than a specific job index.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            long delivered = jobIds.stream()
                    .map(id -> notificationJobRepository.findById(id).orElseThrow().getStatus())
                    .filter(status -> status == NotificationJobStatus.DELIVERED)
                    .count();
            assertThat(delivered).isEqualTo(2);
        });

        long queued = jobIds.stream()
                .map(id -> notificationJobRepository.findById(id).orElseThrow().getStatus())
                .filter(status -> status == NotificationJobStatus.QUEUED)
                .count();
        assertThat(queued).isEqualTo(1);

        assertThat(auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(tenant.getId(), "NOTIFICATION_SENT"))
                .hasSize(2);
        assertThat(auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(tenant.getId(), "RATE_LIMIT_HIT"))
                .hasSize(1);

        assertThat(rateLimiterService.getRemainingTokens(tenant.getId(), DeliveryAttempt.NotificationChannel.EMAIL))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("Sending against an unknown tenant throws and creates no job")
    void testSendNotificationMissingTenant() {
        UUID randomTenantId = UUID.randomUUID();

        assertThatThrownBy(() -> notificationSendService.sendNotification(
                createSendRequest(randomTenantId, UUID.randomUUID(), "a@example.com", Map.of())))
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(notificationJobRepository.count()).isZero();
    }

    @Test
    @DisplayName("Sending against an unknown template throws")
    void testSendNotificationMissingTemplate() {
        Tenant tenant = createTenant("NoTemplateTenant", 1000);
        UUID randomTemplateId = UUID.randomUUID();

        assertThatThrownBy(() -> notificationSendService.sendNotification(
                createSendRequest(tenant.getId(), randomTemplateId, "a@example.com", Map.of())))
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(notificationJobRepository.count()).isZero();
    }

    @Test
    @DisplayName("Missing a required template variable throws and creates no job")
    void testSendNotificationMissingRequiredVariable() {
        Tenant tenant = createTenant("MissingVarTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "greet", "Hello ${firstName}", List.of("EMAIL"));

        assertThatThrownBy(() -> notificationSendService.sendNotification(
                createSendRequest(tenant.getId(), template.getId(), "a@example.com", Map.of())))
                .isInstanceOf(InvalidTemplateException.class)
                .hasMessageContaining("firstName");

        assertThat(notificationJobRepository.count()).isZero();
    }

    @Test
    @DisplayName("Malformed recipient email throws and creates no job")
    void testSendNotificationInvalidEmailFormat() {
        Tenant tenant = createTenant("BadEmailTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "plain", "Hi", List.of("EMAIL"));

        assertThatThrownBy(() -> notificationSendService.sendNotification(
                createSendRequest(tenant.getId(), template.getId(), "not-an-email", Map.of())))
                .isInstanceOf(ValidationException.class);

        assertThat(notificationJobRepository.count()).isZero();
    }

    /**
     * retryFailedNotification() rejects a job whose currentRetry has already reached
     * maxRetries — which is precisely the state a genuinely FAILED job is in. So driving
     * a job through all 6 processing rounds (5 RETRY_SCHEDULED + 1 final FAILED, per the
     * currentRetry/maxRetries check happening before the increment) to reach FAILED, then
     * calling retryFailedNotification() on it, throws IllegalStateException rather than
     * resetting the job — that's the real, current contract, so it's what's asserted here.
     */
    @Test
    @DisplayName("A job that exhausts retries lands on FAILED; retrying it further is rejected")
    void testRetryFailedNotification() {
        Tenant tenant = createTenant("RetryTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "retry", "Test ${id}", List.of("EMAIL"));

        NotificationChannel mockChannel = mock(NotificationChannel.class);
        when(mockChannel.send(any(NotificationJob.class), anyString())).thenReturn(false);
        when(channelFactory.getChannel(anyString())).thenReturn(mockChannel);

        NotificationJobDTO result = notificationSendService.sendNotification(
                createSendRequest(tenant.getId(), template.getId(), "fail@example.com", Map.of("id", "123")));
        UUID jobId = result.id();

        for (int round = 0; round < 6; round++) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                NotificationJobStatus status = notificationJobRepository.findById(jobId).orElseThrow().getStatus();
                assertThat(status).isIn(NotificationJobStatus.SCHEDULED, NotificationJobStatus.FAILED);
            });

            NotificationJob current = notificationJobRepository.findById(jobId).orElseThrow();
            if (current.getStatus() == NotificationJobStatus.FAILED) {
                break;
            }

            // Force the next retry to be due now instead of waiting out the real 2-30s backoff.
            current.setScheduledAt(Instant.now().minusSeconds(1));
            notificationJobRepository.save(current);
            notificationSendService.retryScheduledJobs();
        }

        NotificationJob failedJob = notificationJobRepository.findById(jobId).orElseThrow();
        assertThat(failedJob.getStatus()).isEqualTo(NotificationJobStatus.FAILED);
        assertThat(failedJob.getCurrentRetry()).isEqualTo(5);

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByJobId(jobId);
        assertThat(attempts).hasSize(6);
        assertThat(attempts).allMatch(a -> a.getStatus() == DeliveryAttemptStatus.FAILED);

        assertThat(auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(tenant.getId(), "RETRY_SCHEDULED"))
                .hasSize(5);
        assertThat(auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(tenant.getId(), "NOTIFICATION_FAILED"))
                .hasSize(1);

        assertThatThrownBy(() -> notificationSendService.retryFailedNotification(jobId, tenant.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Cancelling a scheduled job flips it to CANCELLED without ever dispatching")
    void testCancelScheduledNotification() {
        Tenant tenant = createTenant("CancelTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "sched", "Hi", List.of("EMAIL"));
        NotificationChannel mockChannel = mock(NotificationChannel.class);
        when(channelFactory.getChannel(anyString())).thenReturn(mockChannel);

        NotificationJob job = new NotificationJob(tenant.getId(), template.getId(), "cancel@example.com");
        job.setStatus(NotificationJobStatus.SCHEDULED);
        job.setScheduledAt(Instant.now().plusSeconds(3600));
        job.setVariables("{}");
        NotificationJob saved = notificationJobRepository.save(job);

        NotificationJobDTO before = notificationSendService.getNotificationStatus(saved.getId(), tenant.getId());
        assertThat(before.status()).isEqualTo("SCHEDULED");

        notificationSendService.cancelScheduledNotification(saved.getId(), tenant.getId());

        NotificationJob after = notificationJobRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationJobStatus.CANCELLED);

        assertThat(auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(tenant.getId(), "NOTIFICATION_CANCELLED"))
                .hasSize(1);

        verifyNoInteractions(mockChannel);
    }

    @Test
    @DisplayName("getNotificationStatus returns a fully populated DTO including attempts")
    void testGetNotificationStatus() {
        Tenant tenant = createTenant("StatusTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "status", "Hi ${name}", List.of("EMAIL"));
        mockAllChannelsSucceed();

        NotificationJobDTO sent = notificationSendService.sendNotification(
                createSendRequest(tenant.getId(), template.getId(), "status@example.com", Map.of("name", "Bob")));
        UUID jobId = sent.id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationJobRepository.findById(jobId).orElseThrow().getStatus())
                        .isEqualTo(NotificationJobStatus.DELIVERED));

        NotificationJobDTO dto = notificationSendService.getNotificationStatus(jobId, tenant.getId());

        assertThat(dto.attempts()).hasSize(1);
        assertThat(dto.attempts().get(0).channel()).isEqualTo("EMAIL");
        assertThat(dto.attempts().get(0).status()).isEqualTo("SENT");
        assertThat(dto.attempts().get(0).createdAt()).isNotNull();
        assertThat(dto.maxRetries()).isEqualTo(5);
        assertThat(dto.currentRetry()).isZero();
    }

    @Test
    @DisplayName("10 concurrent sends each create a distinct, correctly recorded job")
    void testConcurrentNotificationSends() throws InterruptedException {
        Tenant tenant = createTenant("ConcurrentTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "concurrent", "Hi", List.of("EMAIL"));
        mockAllChannelsSucceed();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<AtomicReference<NotificationJobDTO>> results = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            results.add(new AtomicReference<>());
        }

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    NotificationJobDTO result = notificationSendService.sendNotification(
                            createSendRequest(tenant.getId(), template.getId(), "user" + index + "@example.com", Map.of()));
                    results.get(index).set(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(results).allMatch(ref -> ref.get() != null && ref.get().id() != null);

        List<UUID> jobIds = results.stream().map(ref -> ref.get().id()).toList();
        assertThat(jobIds).doesNotHaveDuplicates();
        assertThat(notificationJobRepository.findAllById(jobIds)).hasSize(threadCount);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long delivered = jobIds.stream()
                    .map(id -> notificationJobRepository.findById(id).orElseThrow().getStatus())
                    .filter(status -> status == NotificationJobStatus.DELIVERED)
                    .count();
            assertThat(delivered).isEqualTo(threadCount);
        });

        assertThat(rateLimiterService.getRemainingTokens(tenant.getId(), DeliveryAttempt.NotificationChannel.EMAIL))
                .isEqualTo(1000 - threadCount);

        long totalAttempts = jobIds.stream().mapToLong(id -> deliveryAttemptRepository.findByJobId(id).size()).sum();
        assertThat(totalAttempts).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("Audit trail records the send with correct resourceId and channel detail")
    void testAuditTrailCompleteness() {
        Tenant tenant = createTenant("AuditTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "audit", "Hi", List.of("EMAIL"));
        mockAllChannelsSucceed();

        NotificationJobDTO result = notificationSendService.sendNotification(
                createSendRequest(tenant.getId(), template.getId(), "audit@example.com", Map.of()));
        UUID jobId = result.id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationJobRepository.findById(jobId).orElseThrow().getStatus())
                        .isEqualTo(NotificationJobStatus.DELIVERED));

        List<AuditLog> logs = auditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenant.getId());
        assertThat(logs).isNotEmpty();
        // findByTenantIdOrderByCreatedAtDesc's own ordering contract: newest first.
        for (int i = 1; i < logs.size(); i++) {
            assertThat(logs.get(i - 1).getCreatedAt()).isAfterOrEqualTo(logs.get(i).getCreatedAt());
        }

        AuditLog sentLog = logs.stream()
                .filter(l -> l.getAction().equals("NOTIFICATION_SENT"))
                .findFirst().orElseThrow();
        assertThat(sentLog.getResourceId()).isEqualTo(jobId);
        assertThat(sentLog.getDetails()).contains("EMAIL");

        // AuditLog is append-only: it exposes createdAt but no updatedAt.
        assertThat(java.util.Arrays.stream(AuditLog.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .contains("createdAt")
                .doesNotContain("updatedAt");
    }

    @Test
    @DisplayName("A template with 3 channels dispatches to and audits all 3")
    void testMultiChannelNotification() {
        Tenant tenant = createTenant("MultiChannelTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "multi", "${msg}",
                List.of("EMAIL", "SMS", "PUSH"));

        NotificationChannel emailChannel = mock(NotificationChannel.class);
        NotificationChannel smsChannel = mock(NotificationChannel.class);
        NotificationChannel pushChannel = mock(NotificationChannel.class);
        when(emailChannel.send(any(NotificationJob.class), anyString())).thenReturn(true);
        when(smsChannel.send(any(NotificationJob.class), anyString())).thenReturn(true);
        when(pushChannel.send(any(NotificationJob.class), anyString())).thenReturn(true);
        when(channelFactory.getChannel(eq("EMAIL"))).thenReturn(emailChannel);
        when(channelFactory.getChannel(eq("SMS"))).thenReturn(smsChannel);
        when(channelFactory.getChannel(eq("PUSH"))).thenReturn(pushChannel);

        NotificationJobDTO result = notificationSendService.sendNotification(
                createSendRequest(tenant.getId(), template.getId(), "multi@example.com", Map.of("msg", "hello")));
        UUID jobId = result.id();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(deliveryAttemptRepository.findByJobId(jobId)).hasSize(3));

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByJobId(jobId);
        assertThat(attempts.stream().map(a -> a.getChannel().name()).toList())
                .containsExactlyInAnyOrder("EMAIL", "SMS", "PUSH");
        assertThat(attempts).allMatch(a -> a.getStatus() == DeliveryAttemptStatus.SENT);

        ArgumentCaptor<String> emailBody = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> smsBody = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> pushBody = ArgumentCaptor.forClass(String.class);
        verify(emailChannel).send(any(NotificationJob.class), emailBody.capture());
        verify(smsChannel).send(any(NotificationJob.class), smsBody.capture());
        verify(pushChannel).send(any(NotificationJob.class), pushBody.capture());
        assertThat(emailBody.getValue()).isEqualTo("hello");
        assertThat(smsBody.getValue()).isEqualTo("hello");
        assertThat(pushBody.getValue()).isEqualTo("hello");

        assertThat(auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(tenant.getId(), "NOTIFICATION_SENT"))
                .hasSize(3);
    }

    @Test
    @DisplayName("retryScheduledJobs() dispatches a due SCHEDULED job")
    void testRetryScheduledJobs() {
        Tenant tenant = createTenant("ScheduledTenant", 1000);
        NotificationTemplate template = createTemplate(tenant.getId(), "due", "Hi", List.of("EMAIL"));
        mockAllChannelsSucceed();

        NotificationJob job = new NotificationJob(tenant.getId(), template.getId(), "due@example.com");
        job.setStatus(NotificationJobStatus.SCHEDULED);
        job.setScheduledAt(Instant.now().minusSeconds(10));
        job.setVariables("{}");
        NotificationJob saved = notificationJobRepository.save(job);

        notificationSendService.retryScheduledJobs();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            NotificationJobStatus status = notificationJobRepository.findById(saved.getId()).orElseThrow().getStatus();
            assertThat(status).isIn(NotificationJobStatus.DELIVERED, NotificationJobStatus.FAILED);
        });

        NotificationJob updated = notificationJobRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationJobStatus.DELIVERED);

        assertThat(auditLogRepository.findByTenantIdAndActionOrderByCreatedAtDesc(tenant.getId(), "NOTIFICATION_SENT"))
                .hasSize(1);
    }
}
