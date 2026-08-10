package com.example.notificationservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_jobs", indexes = {
        @Index(columnList = "tenant_id, status"),
        @Index(columnList = "tenant_id, created_at"),
        @Index(columnList = "scheduled_at, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Column(name = "recipient_phone")
    private String recipientPhone;

    @Column(columnDefinition = "TEXT")
    private String variables; // JSON object

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private NotificationJobStatus status = NotificationJobStatus.QUEUED;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Builder.Default
    @Column(nullable = false)
    private Integer maxRetries = 5;

    @Builder.Default
    @Column(nullable = false)
    private Integer currentRetry = 0;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public NotificationJob(UUID tenantId, UUID templateId, String recipientEmail) {
        this.tenantId = tenantId;
        this.templateId = templateId;
        this.recipientEmail = recipientEmail;
        this.status = NotificationJobStatus.QUEUED;
        this.maxRetries = 5;
        this.currentRetry = 0;
    }

    public enum NotificationJobStatus {
        QUEUED, SCHEDULED, IN_PROGRESS, DELIVERED, FAILED, CANCELLED, DEAD_LETTERED
    }
}