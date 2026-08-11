package com.example.notificationservice.controller;

import com.example.notificationservice.dto.NotificationJobDTO;
import com.example.notificationservice.dto.TenantScopedRequest;
import com.example.notificationservice.service.DeadLetterQueueService;
import com.example.notificationservice.service.NotificationSendService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
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
import java.util.UUID;

/**
 * Same tenant-isolation caveat as the other controllers: there's no mapping from an
 * authenticated principal to a tenantId anywhere in this system, so "wrong tenant" here
 * surfaces as 404 (the tenantId+jobId lookup simply finds nothing), not the 403 an ownership
 * check would produce. See {@code NotificationController}'s class Javadoc for the full note.
 */
@RestController
@RequestMapping("/api/dlq")
@Validated
public class DeadLetterQueueController {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueController.class);

    private final DeadLetterQueueService deadLetterQueueService;
    // Not in the spec's listed dependencies: listDeadLettered() returns Page<NotificationJob>
    // (entities), but this endpoint must return Page<NotificationJobDTO>. NotificationSendService
    // already owns the entity-to-DTO conversion (toNotificationJobDTO), so it's reused here
    // rather than duplicating that mapping logic in the controller.
    private final NotificationSendService notificationSendService;

    public DeadLetterQueueController(DeadLetterQueueService deadLetterQueueService,
            NotificationSendService notificationSendService) {
        this.deadLetterQueueService = deadLetterQueueService;
        this.notificationSendService = notificationSendService;
    }

    @GetMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Page<NotificationJobDTO>> listDeadLettered(
            @RequestParam UUID tenantId,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "0") int pageNum,
            Principal principal) {
        log.debug("Received GET request to /api/dlq from user {}", principal.getName());

        Page<NotificationJobDTO> page = deadLetterQueueService.listDeadLettered(tenantId, pageSize, pageNum)
                .map(notificationSendService::toNotificationJobDTO);

        return ResponseEntity.ok(page);
    }

    @PostMapping("/{jobId}/retry")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<NotificationJobDTO> retryFromDlq(
            @PathVariable UUID jobId, @Valid @RequestBody TenantScopedRequest request, Principal principal) {
        log.debug("Received POST request to /api/dlq/{}/retry from user {}", jobId, principal.getName());

        NotificationJobDTO result = deadLetterQueueService.retryFromDLQ(jobId, request.tenantId());
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{jobId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteFromDlq(
            @PathVariable UUID jobId, @Valid @RequestBody TenantScopedRequest request, Principal principal) {
        log.debug("Received DELETE request to /api/dlq/{} from user {}", jobId, principal.getName());

        deadLetterQueueService.permanentlyDeleteFromDLQ(jobId, request.tenantId());
        return ResponseEntity.noContent().build();
    }
}
