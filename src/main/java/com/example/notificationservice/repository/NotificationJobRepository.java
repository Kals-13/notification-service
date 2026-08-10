package com.example.notificationservice.repository;

import com.example.notificationservice.domain.NotificationJob;
import com.example.notificationservice.domain.NotificationJob.NotificationJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationJobRepository extends JpaRepository<NotificationJob, UUID> {

    Optional<NotificationJob> findByIdAndTenantId(UUID id, UUID tenantId);

    List<NotificationJob> findByTenantIdAndStatus(UUID tenantId, NotificationJobStatus status);

    List<NotificationJob> findByTenantIdAndStatusAndCreatedAtAfter(UUID tenantId, NotificationJobStatus status, Instant after);

    Page<NotificationJob> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<NotificationJob> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, NotificationJobStatus status, Pageable pageable);

    Page<NotificationJob> findByTenantIdAndCreatedAtBetweenOrderByCreatedAtDesc(UUID tenantId, Instant startDate, Instant endDate, Pageable pageable);

    @Query("SELECT j FROM NotificationJob j WHERE j.status = :status AND j.currentRetry >= j.maxRetries")
    List<NotificationJob> findJobsExceedingMaxRetries(@Param("status") NotificationJobStatus status);

    @Query("SELECT j FROM NotificationJob j WHERE j.status = 'SCHEDULED' AND j.scheduledAt <= :now")
    List<NotificationJob> findDueScheduledJobs(@Param("now") Instant now);
}
