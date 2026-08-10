package com.example.notificationservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "email_rate_limit", nullable = false)
    @Builder.Default
    private Integer emailRateLimit = 1000;

    @Column(name = "sms_rate_limit", nullable = false)
    @Builder.Default
    private Integer smsRateLimit = 500;

    @Column(name = "push_rate_limit", nullable = false)
    @Builder.Default
    private Integer pushRateLimit = 500;

    @Column(name = "inapp_rate_limit", nullable = false)
    @Builder.Default
    private Integer inappRateLimit = 2000;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Tenant(String name) {
        this.name = name;
        this.emailRateLimit = 1000;
        this.smsRateLimit = 500;
        this.pushRateLimit = 500;
        this.inappRateLimit = 2000;
    }
}
