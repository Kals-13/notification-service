package com.example.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(
        @NotBlank(message = "is required") String name,
        @JsonProperty("email_rate_limit") Integer emailRateLimit,
        @JsonProperty("sms_rate_limit") Integer smsRateLimit,
        @JsonProperty("push_rate_limit") Integer pushRateLimit,
        @JsonProperty("inapp_rate_limit") Integer inappRateLimit) {
}
