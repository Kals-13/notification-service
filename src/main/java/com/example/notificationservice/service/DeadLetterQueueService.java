package com.example.notificationservice.service;

import com.example.notificationservice.domain.NotificationJob;
import com.example.notificationservice.domain.NotificationJob.NotificationJobStatus;
import com.example.notificationservice.dto.NotificationJobDTO;
import com.example.notificationservice.event.MaxRetriesExceededEvent;
import com.example.notificationservice.exception.EntityNotFoundException;
import com.example.notificationservice.repository.DeliveryAttemptRepository;
import com.example.notificationservice.repository.NotificationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Manages notification jobs that have exhausted their retry budget: promotes them from
 * FAILED to DEAD_LETTERED (in response to {@link MaxRetriesExceededEvent}, published by
 * NotificationSendService rather than called directly, since this class already depends on
 * NotificationSendService to redispatch a job retried back out of the DLQ — a direct call
 * the other way would be a circular bean dependency), and lets an operator list, retry, or
 * permanently delete them.
 */
@Service
public class DeadLetterQueueService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueService.class);

    private final NotificationJobRepository notificationJobRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationSendService notificationSendService;
    private final AuditService auditService;

    public DeadLetterQueueService(
            NotificationJobRepository notificationJobRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            NotificationSendService notificationSendService,
            AuditService auditService) {
        this.notificationJobRepository = notificationJobRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.notificationSendService = notificationSendService;
        this.auditService = auditService;
    }

    @EventListener
    public void onMaxRetriesExceeded(MaxRetriesExceededEvent event) {
        try {
            moveToDeadLetterQueue(event.jobId(), event.reason());
        } catch (Exception e) {
            log.error("Failed to move job {} to the DLQ after max retries exceeded", event.jobId(), e);
        }
    }

    public NotificationJob moveToDeadLetterQueue(UUID jobId, String reason) {
        NotificationJob job = notificationJobRepository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Notification job not found: " + jobId));

        if (job.getStatus() != NotificationJobStatus.FAILED) {
            throw new IllegalStateException(
                    "Job " + jobId + " is not in FAILED status (current status: " + job.getStatus()
                            + ") and cannot be moved to the DLQ");
        }

        job.setStatus(NotificationJobStatus.DEAD_LETTERED);
        NotificationJob saved = notificationJobRepository.save(job);

        auditService.logNotificationDeadLettered(job.getTenantId(), jobId, reason);
        log.info("Moved job {} to DLQ: {}", jobId, reason);

        return saved;
    }

    public Page<NotificationJob> listDeadLettered(UUID tenantId, int pageSize, int pageNum) {
        Pageable pageable = PageRequest.of(pageNum, pageSize, Sort.by("createdAt").descending());
        Page<NotificationJob> page = notificationJobRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(
                tenantId, NotificationJobStatus.DEAD_LETTERED, pageable);

        log.debug("Listed {} dead-lettered jobs for tenant {}", page.getNumberOfElements(), tenantId);
        return page;
    }

    /**
     * Resets and redispatches the same job (rather than reconstructing a SendNotificationRequest
     * and calling sendNotification(), which would create a brand-new, separate job row and leave
     * this one behind still marked QUEUED but never actually dispatched). The dispatch pipeline
     * already re-fetches the template and re-parses the job's stored variables from scratch, so
     * there's nothing to reconstruct here.
     */
    public NotificationJobDTO retryFromDLQ(UUID jobId, UUID tenantId) {
        NotificationJob job = notificationJobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Notification job not found: " + jobId));

        if (job.getStatus() != NotificationJobStatus.DEAD_LETTERED) {
            throw new IllegalStateException(
                    "Job " + jobId + " is not in DEAD_LETTERED status (current status: " + job.getStatus()
                            + ") and cannot be retried from the DLQ");
        }

        job.setStatus(NotificationJobStatus.QUEUED);
        job.setCurrentRetry(0);
        job.setLastError(null);
        NotificationJob saved = notificationJobRepository.save(job);

        notificationSendService.redispatchJob(saved.getId(), tenantId);
        auditService.logNotificationRetriedFromDLQ(tenantId, jobId);
        log.info("Retried job {} from DLQ", jobId);

        return notificationSendService.toNotificationJobDTO(saved);
    }

    public void permanentlyDeleteFromDLQ(UUID jobId, UUID tenantId) {
        NotificationJob job = notificationJobRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Notification job not found: " + jobId));

        if (job.getStatus() != NotificationJobStatus.DEAD_LETTERED) {
            throw new IllegalStateException(
                    "Job " + jobId + " is not in DEAD_LETTERED status (current status: " + job.getStatus()
                            + ") and cannot be deleted from the DLQ");
        }

        // No FK/cascade exists between DeliveryAttempt and NotificationJob (jobId is a plain
        // column, not a JPA relationship), so a permanent delete needs to clean these up itself
        // to avoid leaving orphaned attempt rows behind.
        deliveryAttemptRepository.deleteAll(deliveryAttemptRepository.findByJobId(jobId));
        notificationJobRepository.delete(job);

        auditService.log(tenantId, "NOTIFICATION_DELETED_FROM_DLQ", "NOTIFICATION", jobId, "{}");
        log.info("Permanently deleted job {} from DLQ", jobId);
    }
}
