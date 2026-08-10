package com.example.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record TenantDTO(
        UUID id,
        String name,
        @JsonProperty("email_rate_limit") Integer emailRateLimit,
        @JsonProperty("sms_rate_limit") Integer smsRateLimit,
        @JsonProperty("push_rate_limit") Integer pushRateLimit,
        @JsonProperty("inapp_rate_limit") Integer inappRateLimit,
        Instant createdAt,
        Instant updatedAt) {
}
