package com.example.notificationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record SendNotificationRequest(
        @NotNull(message = "is required") UUID tenantId,
        @NotNull(message = "is required") UUID templateId,
        @NotBlank(message = "is required") String recipientEmail,
        String recipientPhone,
        Map<String, String> variables) {

    public SendNotificationRequest {
        if (variables == null) {
            variables = Map.of();
        }
    }
}
