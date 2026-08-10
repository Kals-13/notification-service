package com.example.notificationservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationJobDTO(
        UUID id,
        UUID tenantId,
        UUID templateId,
        String recipientEmail,
        String status,
        Instant deliveredAt,
        List<DeliveryAttemptDTO> attempts,
        int maxRetries,
        int currentRetry,
        Instant createdAt) {
}
