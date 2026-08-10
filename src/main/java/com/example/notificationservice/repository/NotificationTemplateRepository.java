package com.example.notificationservice.repository;

import com.example.notificationservice.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    List<NotificationTemplate> findByTenantId(UUID tenantId);

    Optional<NotificationTemplate> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<NotificationTemplate> findByTenantIdAndName(UUID tenantId, String name);

    List<NotificationTemplate> findByTenantIdAndIsActiveTrue(UUID tenantId);
}
