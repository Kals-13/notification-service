package com.example.notificationservice.controller;

import com.example.notificationservice.domain.Tenant;
import com.example.notificationservice.dto.CreateTenantRequest;
import com.example.notificationservice.dto.TenantDTO;
import com.example.notificationservice.dto.UpdateLimitsRequest;
import com.example.notificationservice.exception.EntityNotFoundException;
import com.example.notificationservice.repository.TenantRepository;
import com.example.notificationservice.service.AuditService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants")
@Validated
public class TenantController {

    private static final Logger log = LoggerFactory.getLogger(TenantController.class);

    // Matches Tenant's own documented defaults; a request that omits a limit gets these
    // rather than a null column value.
    private static final int DEFAULT_EMAIL_RATE_LIMIT = 1000;
    private static final int DEFAULT_SMS_RATE_LIMIT = 500;
    private static final int DEFAULT_PUSH_RATE_LIMIT = 500;
    private static final int DEFAULT_INAPP_RATE_LIMIT = 2000;

    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public TenantController(TenantRepository tenantRepository, AuditService auditService) {
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantDTO> createTenant(@Valid @RequestBody CreateTenantRequest request, Principal principal) {
        log.debug("Received POST request to /api/tenants from user {}", principal.getName());

        Tenant tenant = Tenant.builder()
                .name(request.name())
                .emailRateLimit(request.emailRateLimit() != null ? request.emailRateLimit() : DEFAULT_EMAIL_RATE_LIMIT)
                .smsRateLimit(request.smsRateLimit() != null ? request.smsRateLimit() : DEFAULT_SMS_RATE_LIMIT)
                .pushRateLimit(request.pushRateLimit() != null ? request.pushRateLimit() : DEFAULT_PUSH_RATE_LIMIT)
                .inappRateLimit(request.inappRateLimit() != null ? request.inappRateLimit() : DEFAULT_INAPP_RATE_LIMIT)
                .build();

        Tenant saved = tenantRepository.save(tenant);
        auditService.logTenantCreated(saved.getId(), saved.getName());

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    @GetMapping("/{tenantId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantDTO> getTenant(@PathVariable UUID tenantId, Principal principal) {
        log.debug("Received GET request to /api/tenants/{} from user {}", tenantId, principal.getName());

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantId));

        return ResponseEntity.ok(toDto(tenant));
    }

    @PutMapping("/{tenantId}/limits")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantDTO> updateLimits(@PathVariable UUID tenantId,
            @Valid @RequestBody UpdateLimitsRequest request, Principal principal) {
        log.debug("Received PUT request to /api/tenants/{}/limits from user {}", tenantId, principal.getName());

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantId));

        if (request.emailRateLimit() != null) {
            tenant.setEmailRateLimit(request.emailRateLimit());
        }
        if (request.smsRateLimit() != null) {
            tenant.setSmsRateLimit(request.smsRateLimit());
        }
        if (request.pushRateLimit() != null) {
            tenant.setPushRateLimit(request.pushRateLimit());
        }
        if (request.inappRateLimit() != null) {
            tenant.setInappRateLimit(request.inappRateLimit());
        }

        Tenant saved = tenantRepository.save(tenant);
        auditService.logRateLimitsUpdated(tenantId);

        return ResponseEntity.ok(toDto(saved));
    }

    @DeleteMapping("/{tenantId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Void> deleteTenant(@PathVariable UUID tenantId, Principal principal) {
        log.debug("Received DELETE request to /api/tenants/{} from user {}", tenantId, principal.getName());

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + tenantId));

        tenantRepository.delete(tenant);
        auditService.logTenantDeleted(tenantId, tenant.getName());

        return ResponseEntity.noContent().build();
    }

    private TenantDTO toDto(Tenant tenant) {
        return new TenantDTO(
                tenant.getId(),
                tenant.getName(),
                tenant.getEmailRateLimit(),
                tenant.getSmsRateLimit(),
                tenant.getPushRateLimit(),
                tenant.getInappRateLimit(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt());
    }
}
