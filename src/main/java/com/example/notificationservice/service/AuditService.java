package com.example.notificationservice.service;

import com.example.notificationservice.domain.AuditLog;
import com.example.notificationservice.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public AuditLog log(UUID tenantId, String action, String resourceType, UUID resourceId, String details) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .tenantId(tenantId)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .details(details)
                    .build();

            AuditLog saved = auditLogRepository.save(auditLog);
            log.info("Audit: tenant={}, action={}, resourceType={}, resourceId={}", tenantId, action, resourceType, resourceId);
            return saved;
        } catch (Exception e) {
            log.error("Failed to write audit log: tenant={}, action={}, resourceType={}, resourceId={}",
                    tenantId, action, resourceType, resourceId, e);
            return null;
        }
    }

    public void logNotificationSent(UUID tenantId, UUID jobId, String channel) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("channel", channel);
        log(tenantId, "NOTIFICATION_SENT", "NOTIFICATION", jobId, toJson(details));
    }

    public void logRateLimitHit(UUID tenantId, UUID jobId, String channel, int remainingTokens) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("channel", channel);
        details.put("remainingTokens", remainingTokens);
        log(tenantId, "RATE_LIMIT_HIT", "NOTIFICATION", jobId, toJson(details));
    }

    public void logRetryScheduled(UUID tenantId, UUID jobId, String reason, Instant nextRetryAt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        details.put("nextRetryAt", nextRetryAt == null ? null : nextRetryAt.toString());
        log(tenantId, "RETRY_SCHEDULED", "NOTIFICATION", jobId, toJson(details));
    }

    public void logNotificationFailed(UUID tenantId, UUID jobId, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        log(tenantId, "NOTIFICATION_FAILED", "NOTIFICATION", jobId, toJson(details));
    }

    public void logTemplateCreated(UUID tenantId, UUID templateId, String templateName) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("templateName", templateName);
        log(tenantId, "TEMPLATE_CREATED", "TEMPLATE", templateId, toJson(details));
    }

    public void logNotificationCancelled(UUID tenantId, UUID jobId) {
        log(tenantId, "NOTIFICATION_CANCELLED", "NOTIFICATION", jobId, toJson(new LinkedHashMap<>()));
    }

    private String toJson(Map<String, Object> details) {
        try {
            return OBJECT_MAPPER.writeValueAsString(details);
        } catch (JacksonException e) {
            log.error("Failed to serialize audit details to JSON: {}", details, e);
            return details.toString();
        }
    }
}
