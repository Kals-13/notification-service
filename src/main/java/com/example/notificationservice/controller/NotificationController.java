package com.example.notificationservice.controller;

import com.example.notificationservice.domain.NotificationJob;
import com.example.notificationservice.domain.NotificationJob.NotificationJobStatus;
import com.example.notificationservice.dto.NotificationJobDTO;
import com.example.notificationservice.dto.SendNotificationRequest;
import com.example.notificationservice.dto.TenantScopedRequest;
import com.example.notificationservice.exception.ValidationException;
import com.example.notificationservice.repository.NotificationJobRepository;
import com.example.notificationservice.service.NotificationSendService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * "Tenant admin can only operate on their own tenant" is enforced only up to what the
 * data model supports today: every lookup here is scoped by the tenantId the caller
 * supplies (jobId+tenantId together, 404 if they don't match), and role checks gate which
 * endpoints a caller can reach at all. There is no mapping anywhere in this system from an
 * authenticated principal to a specific tenantId — SecurityConfig's in-memory users carry a
 * role only — so nothing here stops an authenticated TENANT_ADMIN from passing a *different*
 * tenant's ID and operating on it. Closing that gap needs a real identity model (e.g. a
 * tenantId claim/attribute on the principal) that doesn't exist in this codebase yet.
 */
@RestController
@RequestMapping("/api/notifications")
@Validated
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationSendService notificationSendService;
    private final NotificationJobRepository notificationJobRepository;

    public NotificationController(NotificationSendService notificationSendService,
            NotificationJobRepository notificationJobRepository) {
        this.notificationSendService = notificationSendService;
        this.notificationJobRepository = notificationJobRepository;
    }

    @PostMapping("/send")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<NotificationJobDTO> sendNotification(
            @Valid @RequestBody SendNotificationRequest request, HttpServletRequest httpRequest, Principal principal) {
        log.debug("Received POST request to /api/notifications/send from user {}", principal.getName());

        NotificationJobDTO result = notificationSendService.sendNotification(request, httpRequest);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{jobId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<NotificationJobDTO> getNotification(
            @PathVariable UUID jobId, @RequestParam UUID tenantId, Principal principal) {
        log.debug("Received GET request to /api/notifications/{} from user {}", jobId, principal.getName());

        NotificationJobDTO result = notificationSendService.getNotificationStatus(jobId, tenantId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/report")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Page<NotificationJobDTO>> getReport(
            @RequestParam UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "0") int pageNum,
            Principal principal) {
        log.debug("Received GET request to /api/notifications/report from user {}", principal.getName());

        NotificationJobStatus statusFilter = parseStatus(status);
        var startInstant = startDate == null ? null : startDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        var endInstant = endDate == null ? null
                : endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minus(1, ChronoUnit.NANOS);

        Page<NotificationJob> jobs = notificationJobRepository.findByFilters(
                tenantId, statusFilter, startInstant, endInstant, PageRequest.of(pageNum, pageSize));

        return ResponseEntity.ok(jobs.map(notificationSendService::toNotificationJobDTO));
    }

    @PostMapping("/{jobId}/retry")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<NotificationJobDTO> retryNotification(
            @PathVariable UUID jobId, @Valid @RequestBody TenantScopedRequest request, Principal principal) {
        log.debug("Received POST request to /api/notifications/{}/retry from user {}", jobId, principal.getName());

        NotificationJobDTO result = notificationSendService.retryFailedNotification(jobId, request.tenantId());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{jobId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> cancelNotification(
            @PathVariable UUID jobId, @Valid @RequestBody TenantScopedRequest request, Principal principal) {
        log.debug("Received DELETE request to /api/notifications/{} from user {}", jobId, principal.getName());

        notificationSendService.cancelScheduledNotification(jobId, request.tenantId());
        return ResponseEntity.noContent().build();
    }

    private NotificationJobStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return NotificationJobStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid status: " + status);
        }
    }
}
