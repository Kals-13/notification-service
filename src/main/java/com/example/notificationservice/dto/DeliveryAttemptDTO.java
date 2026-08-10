package com.example.notificationservice.dto;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptDTO(
        UUID id,
        String channel,
        String status,
        String errorMessage,
        int attemptNumber,
        Instant createdAt) {
}
