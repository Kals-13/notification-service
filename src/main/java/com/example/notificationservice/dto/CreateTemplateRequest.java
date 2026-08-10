package com.example.notificationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateTemplateRequest(
        @NotNull(message = "is required") UUID tenantId,
        @NotBlank(message = "is required") String name,
        @NotBlank(message = "is required") String subject,
        @NotBlank(message = "is required") String body,
        @NotEmpty(message = "must contain at least one channel") List<String> channels) {
}
