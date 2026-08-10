package com.example.notificationservice.repository;

import com.example.notificationservice.domain.DeliveryAttempt;
import com.example.notificationservice.domain.DeliveryAttempt.DeliveryAttemptStatus;
import com.example.notificationservice.domain.DeliveryAttempt.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByJobIdOrderByAttemptNumberDesc(UUID jobId);

    Optional<DeliveryAttempt> findByJobIdAndChannel(UUID jobId, NotificationChannel channel);

    List<DeliveryAttempt> findByJobIdAndChannelAndStatus(UUID jobId, NotificationChannel channel, DeliveryAttemptStatus status);

    List<DeliveryAttempt> findByJobId(UUID jobId);
}
