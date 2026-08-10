package com.example.notificationservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationTemplateDTO(
        UUID id,
        UUID tenantId,
        String name,
        String subject,
        String body,
        List<String> channels,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt) {
}
