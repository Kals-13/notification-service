package com.example.notificationservice.dto;

import java.util.Map;
import java.util.UUID;

public record SendNotificationRequest(
        UUID tenantId,
        UUID templateId,
        String recipientEmail,
        String recipientPhone,
        Map<String, String> variables) {

    public SendNotificationRequest {
        if (variables == null) {
            variables = Map.of();
        }
    }
}
