package com.example.notificationservice.event;

import java.util.UUID;

/**
 * Published when a notification job exhausts its retry budget on a channel attempt.
 * Exists so NotificationSendService and DeadLetterQueueService don't need a direct
 * circular dependency on each other (DeadLetterQueueService already depends on
 * NotificationSendService to redispatch a job retried out of the DLQ).
 */
public record MaxRetriesExceededEvent(UUID jobId, String reason) {
}
