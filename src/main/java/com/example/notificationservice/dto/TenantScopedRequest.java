package com.example.notificationservice.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Minimal request body shape for endpoints that only need to carry the caller's
 * tenantId (e.g. DELETE/retry requests where the identifier is otherwise a path variable).
 */
public record TenantScopedRequest(@NotNull(message = "is required") UUID tenantId) {
}
