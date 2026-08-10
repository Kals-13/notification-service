package com.example.notificationservice.dto;

import java.time.Instant;

public record ErrorResponse(
        String error,
        String code,
        Instant timestamp,
        String path,
        String details) {
}
