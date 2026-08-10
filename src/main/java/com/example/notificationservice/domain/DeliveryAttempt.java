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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts", indexes = {
        @Index(columnList = "job_id, attempt_number")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private DeliveryAttemptStatus status = DeliveryAttemptStatus.PENDING;

    @Column(name = "error_message")
    private String errorMessage;

    @Builder.Default
    @Column(nullable = false)
    private Integer attemptNumber = 1;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public DeliveryAttempt(UUID jobId, NotificationChannel channel) {
        this.jobId = jobId;
        this.channel = channel;
        this.status = DeliveryAttemptStatus.PENDING;
        this.attemptNumber = 1;
    }

    public enum NotificationChannel {
        EMAIL, SMS, PUSH, INAPP
    }

    public enum DeliveryAttemptStatus {
        PENDING, SENT, FAILED, RETRY
    }
}