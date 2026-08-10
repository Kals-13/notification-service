package com.example.notificationservice.service;

import com.example.notificationservice.channel.ChannelFactory;
import com.example.notificationservice.channel.NotificationChannel;
import com.example.notificationservice.domain.DeliveryAttempt;
import com.example.notificationservice.domain.DeliveryAttempt.DeliveryAttemptStatus;
import com.example.notificationservice.domain.NotificationJob;
import com.example.notificationservice.domain.NotificationJob.NotificationJobStatus;
import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.dto.DeliveryAttemptDTO;
import com.example.notificationservice.dto.NotificationJobDTO;
import com.example.notificationservice.dto.SendNotificationRequest;
import com.example.notificationservice.exception.EntityNotFoundException;
import com.example.notificationservice.exception.ValidationException;
import com.example.notificationservice.repository.DeliveryAttemptRepository;
import com.example.notificationservice.repository.NotificationJobRepository;
import com.example.notificationservice.repository.NotificationTemplateRepository;
import com.example.notificationservice.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

/**
 * Heart of the send flow: validates and queues a notification, then dispatches it
 * asynchronously across each channel the template supports, applying rate limiting
 * and retry/backoff around every channel attempt.
 */
@Service
public class NotificationSendService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSendService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final NotificationJobRepository notificationJobRepository;
    private final NotificationTemplateRepository notificationTemplateRepository;
    private final TenantRepository tenantRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final RateLimiterService rateLimiterService;
    private final RetryService retryService;
    private final ChannelFactory channelFactory;
    private final VariableSubstitutionService variableSubstitutionService;
    private final AuditService auditService;
    private final ScheduledExecutorService scheduledExecutorService;

    public NotificationSendService(
            NotificationJobRepository notificationJobRepository,
            NotificationTemplateRepository notificationTemplateRepository,
            TenantRepository tenantRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            RateLimiterService rateLimiterService,
            RetryService retryService,
            ChannelFactory channelFactory,
            VariableSubstitutionService variableSubstitutionService,
            AuditService auditService,
            ScheduledExecutorService scheduledExecutorService) {
        this.notificationJobRepository = notificationJobRepository;
        this.notificationTemplateRepository = notificationTemplateRepository;
        this.tenantRepository = tenantRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.rateLimiterService = rateLimiterService;
        this.retryService = retryService;
        this.channelFactory = channelFactory;
        this.variableSubstitutionService = variableSubstitutionService;
        this.auditService = auditService;
        this.scheduledExecutorService = scheduledExecutorService;
    }

    public NotificationJobDTO sendNotification(SendNotificationRequest request) {
        tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + request.tenantId()));

        NotificationTemplate template = notificationTemplateRepository
                .findByTenantIdAndId(request.tenantId(), request.templateId())
                .orElseThrow(() -> new EntityNotFoundException("Template not found: " + request.templateId()));

        if (!EMAIL_PATTERN.matcher(request.recipientEmail()).matches()) {
            throw new ValidationException("Invalid recipient email format: " + request.recipientEmail());
        }

        Map<String, String> variables = request.variables();
        variableSubstitutionService.validateRequiredVariables(template.getBody(), variables);

        NotificationJob job = new NotificationJob(request.tenantId(), request.templateId(), request.recipientEmail());
        job.setRecipientPhone(request.recipientPhone());
        job.setVariables(serializeVariables(variables));

        NotificationJob savedJob = notificationJobRepository.save(job);

        dispatchAsync(savedJob.getId(), savedJob.getTenantId());

        return toDto(savedJob, List.of());
    }

    public NotificationJobDTO retryFailedNotification(UUID jobId, UUID tenantId) {
        NotificationJob job = notificationJobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Notification job not found: " + jobId));

        if (job.getCurrentRetry() >= job.getMaxRetries()) {
            throw new IllegalStateException(
                    "Job " + jobId + " has exceeded its max retries (" + job.getMaxRetries() + ") and cannot be retried");
        }

        job.setCurrentRetry(0);
        job.setLastError(null);
        job.setStatus(NotificationJobStatus.QUEUED);
        NotificationJob savedJob = notificationJobRepository.save(job);

        dispatchAsync(savedJob.getId(), tenantId);

        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByJobIdOrderByAttemptNumberDesc(jobId);
        return toDto(savedJob, attempts);
    }

    public NotificationJobDTO getNotificationStatus(UUID jobId, UUID tenantId) {
        NotificationJob job = notificationJobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Notification job not found: " + jobId));
        List<DeliveryAttempt> attempts = deliveryAttemptRepository.findByJobIdOrderByAttemptNumberDesc(jobId);
        return toDto(job, attempts);
    }

    public void cancelScheduledNotification(UUID jobId, UUID tenantId) {
        NotificationJob job = notificationJobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Notification job not found: " + jobId));

        if (job.getStatus() != NotificationJobStatus.SCHEDULED) {
            throw new IllegalStateException(
                    "Job " + jobId + " is not in SCHEDULED status (current status: " + job.getStatus() + ")");
        }

        job.setStatus(NotificationJobStatus.CANCELLED);
        notificationJobRepository.save(job);
        auditService.logNotificationCancelled(tenantId, jobId);
    }

    @Scheduled(fixedDelay = 60_000)
    public void retryScheduledJobs() {
        List<NotificationJob> dueJobs = notificationJobRepository.findDueScheduledJobs(Instant.now());
        log.info("Processing {} due scheduled jobs", dueJobs.size());
        for (NotificationJob job : dueJobs) {
            dispatchAsync(job.getId(), job.getTenantId());
        }
    }

    private void dispatchAsync(UUID jobId, UUID tenantId) {
        scheduledExecutorService.submit(() -> {
            try {
                processJob(jobId, tenantId);
            } catch (Exception e) {
                log.error("Unhandled exception during async dispatch for job {}", jobId, e);
            }
        });
    }

    private void processJob(UUID jobId, UUID tenantId) {
        NotificationJob job = notificationJobRepository.findByIdAndTenantId(jobId, tenantId).orElse(null);
        if (job == null) {
            log.error("Notification job {} not found for tenant {} during async dispatch", jobId, tenantId);
            return;
        }

        NotificationTemplate template = notificationTemplateRepository
                .findByTenantIdAndId(tenantId, job.getTemplateId()).orElse(null);
        if (template == null) {
            failJob(job, tenantId, "Template not found: " + job.getTemplateId());
            return;
        }

        List<String> channelNames;
        try {
            channelNames = parseChannels(template.getChannels());
        } catch (JacksonException e) {
            log.error("Failed to parse channels for template {}, job {}", template.getId(), jobId, e);
            failJob(job, tenantId, "Invalid channel configuration: " + e.getMessage());
            return;
        }

        if (channelNames.isEmpty()) {
            log.warn("Template {} has no channels configured, failing job {}", template.getId(), jobId);
            failJob(job, tenantId, "No channels configured for template");
            return;
        }

        Map<String, String> variables = parseVariables(job.getVariables());

        for (String channelName : channelNames) {
            processChannel(job, tenantId, template, channelName, variables);
        }
    }

    private void processChannel(NotificationJob job, UUID tenantId, NotificationTemplate template,
                                 String channelName, Map<String, String> variables) {
        UUID jobId = job.getId();

        DeliveryAttempt.NotificationChannel channelEnum;
        try {
            channelEnum = DeliveryAttempt.NotificationChannel.valueOf(channelName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.error("Unknown channel '{}' configured on template {} for job {}", channelName, template.getId(), jobId);
            return;
        }

        try {
            boolean allowed = rateLimiterService.checkAndConsume(tenantId, channelEnum);
            if (!allowed) {
                int remaining = rateLimiterService.getRemainingTokens(tenantId, channelEnum);
                log.info("Rate limit hit for tenant {}, channel {}, job {}", tenantId, channelEnum, jobId);
                auditService.logRateLimitHit(tenantId, jobId, channelName, remaining);
                return;
            }

            String renderedBody = variableSubstitutionService.render(template.getBody(), variables);

            NotificationChannel channelImpl = channelFactory.getChannel(channelName);
            boolean sent = channelImpl.send(job, renderedBody);
            if (!sent) {
                throw new IllegalStateException("Channel " + channelName + " reported delivery failure");
            }

            DeliveryAttempt attempt = DeliveryAttempt.builder()
                    .jobId(jobId)
                    .channel(channelEnum)
                    .status(DeliveryAttemptStatus.SENT)
                    .attemptNumber(job.getCurrentRetry() + 1)
                    .build();
            deliveryAttemptRepository.save(attempt);

            job.setStatus(NotificationJobStatus.DELIVERED);
            job.setLastError(null);
            notificationJobRepository.save(job);
            auditService.logNotificationSent(tenantId, jobId, channelName);
        } catch (Exception channelException) {
            log.error("Failed to send notification via channel {} for job {}", channelEnum, jobId, channelException);

            DeliveryAttempt failedAttempt = DeliveryAttempt.builder()
                    .jobId(jobId)
                    .channel(channelEnum)
                    .status(DeliveryAttemptStatus.FAILED)
                    .errorMessage(channelException.getMessage())
                    .attemptNumber(job.getCurrentRetry() + 1)
                    .build();
            deliveryAttemptRepository.save(failedAttempt);

            if (job.getCurrentRetry() < job.getMaxRetries()) {
                job.setCurrentRetry(job.getCurrentRetry() + 1);
                Instant nextRetryAt = retryService.getNextRetryTime(job.getCurrentRetry());
                job.setLastError(channelException.getMessage());
                job.setScheduledAt(nextRetryAt);
                job.setStatus(NotificationJobStatus.SCHEDULED);
                notificationJobRepository.save(job);
                auditService.logRetryScheduled(tenantId, jobId, channelException.getMessage(), nextRetryAt);
            } else {
                failJob(job, tenantId, channelException.getMessage());
            }
        }
    }

    private void failJob(NotificationJob job, UUID tenantId, String reason) {
        job.setStatus(NotificationJobStatus.FAILED);
        job.setLastError(reason);
        notificationJobRepository.save(job);
        auditService.logNotificationFailed(tenantId, job.getId(), reason);
    }

    private NotificationJobDTO toDto(NotificationJob job, List<DeliveryAttempt> attempts) {
        Instant deliveredAt = job.getStatus() == NotificationJobStatus.DELIVERED ? job.getUpdatedAt() : null;
        List<DeliveryAttemptDTO> attemptDtos = attempts.stream().map(this::toAttemptDto).toList();
        return new NotificationJobDTO(
                job.getId(),
                job.getTenantId(),
                job.getTemplateId(),
                job.getRecipientEmail(),
                job.getStatus().name(),
                deliveredAt,
                attemptDtos,
                job.getMaxRetries(),
                job.getCurrentRetry(),
                job.getCreatedAt());
    }

    private DeliveryAttemptDTO toAttemptDto(DeliveryAttempt attempt) {
        return new DeliveryAttemptDTO(
                attempt.getId(),
                attempt.getChannel().name(),
                attempt.getStatus().name(),
                attempt.getErrorMessage(),
                attempt.getAttemptNumber(),
                attempt.getCreatedAt());
    }

    private Map<String, String> parseVariables(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (JacksonException e) {
            log.error("Failed to parse job variables JSON: {}", json, e);
            return Map.of();
        }
    }

    private String serializeVariables(Map<String, String> variables) {
        try {
            return OBJECT_MAPPER.writeValueAsString(variables);
        } catch (JacksonException e) {
            log.error("Failed to serialize variables to JSON: {}", variables, e);
            return "{}";
        }
    }

    private List<String> parseChannels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() { });
    }
}
