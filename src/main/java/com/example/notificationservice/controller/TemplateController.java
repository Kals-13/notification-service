package com.example.notificationservice.controller;

import com.example.notificationservice.domain.NotificationTemplate;
import com.example.notificationservice.dto.CreateTemplateRequest;
import com.example.notificationservice.dto.NotificationTemplateDTO;
import com.example.notificationservice.dto.TenantScopedRequest;
import com.example.notificationservice.dto.UpdateTemplateRequest;
import com.example.notificationservice.exception.EntityNotFoundException;
import com.example.notificationservice.repository.NotificationTemplateRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
@Validated
public class TemplateController {

    private static final Logger log = LoggerFactory.getLogger(TemplateController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final NotificationTemplateRepository notificationTemplateRepository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public TemplateController(NotificationTemplateRepository notificationTemplateRepository,
            TenantRepository tenantRepository, AuditService auditService) {
        this.notificationTemplateRepository = notificationTemplateRepository;
        this.tenantRepository = tenantRepository;
        this.auditService = auditService;
    }

    @PostMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<NotificationTemplateDTO> createTemplate(
            @Valid @RequestBody CreateTemplateRequest request, Principal principal) {
        log.debug("Received POST request to /api/templates from user {}", principal.getName());

        tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found: " + request.tenantId()));

        NotificationTemplate template = NotificationTemplate.builder()
                .tenantId(request.tenantId())
                .name(request.name())
                .subject(request.subject())
                .body(request.body())
                .channels(toJson(request.channels()))
                .build();

        NotificationTemplate saved = notificationTemplateRepository.save(template);
        auditService.logTemplateCreated(request.tenantId(), saved.getId(), saved.getName());

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    @GetMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<List<NotificationTemplateDTO>> listTemplates(
            @RequestParam UUID tenantId,
            @RequestParam(required = false) Boolean isActive,
            Principal principal) {
        log.debug("Received GET request to /api/templates from user {}", principal.getName());

        List<NotificationTemplate> templates = notificationTemplateRepository.findByTenantId(tenantId);
        if (isActive != null) {
            templates = templates.stream()
                    .filter(t -> isActive.equals(t.getIsActive()))
                    .toList();
        }

        return ResponseEntity.ok(templates.stream().map(this::toDto).toList());
    }

    @GetMapping("/{templateId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<NotificationTemplateDTO> getTemplate(
            @PathVariable UUID templateId, @RequestParam UUID tenantId, Principal principal) {
        log.debug("Received GET request to /api/templates/{} from user {}", templateId, principal.getName());

        NotificationTemplate template = notificationTemplateRepository.findByTenantIdAndId(tenantId, templateId)
                .orElseThrow(() -> new EntityNotFoundException("Template not found: " + templateId));

        return ResponseEntity.ok(toDto(template));
    }

    @PutMapping("/{templateId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<NotificationTemplateDTO> updateTemplate(
            @PathVariable UUID templateId, @Valid @RequestBody UpdateTemplateRequest request, Principal principal) {
        log.debug("Received PUT request to /api/templates/{} from user {}", templateId, principal.getName());

        NotificationTemplate template = notificationTemplateRepository
                .findByTenantIdAndId(request.tenantId(), templateId)
                .orElseThrow(() -> new EntityNotFoundException("Template not found: " + templateId));

        template.setName(request.name());
        template.setSubject(request.subject());
        template.setBody(request.body());
        template.setChannels(toJson(request.channels()));
        if (request.isActive() != null) {
            template.setIsActive(request.isActive());
        }

        NotificationTemplate saved = notificationTemplateRepository.save(template);
        auditService.logTemplateUpdated(request.tenantId(), saved.getId(), saved.getName());

        return ResponseEntity.ok(toDto(saved));
    }

    @DeleteMapping("/{templateId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable UUID templateId, @Valid @RequestBody TenantScopedRequest request, Principal principal) {
        log.debug("Received DELETE request to /api/templates/{} from user {}", templateId, principal.getName());

        NotificationTemplate template = notificationTemplateRepository
                .findByTenantIdAndId(request.tenantId(), templateId)
                .orElseThrow(() -> new EntityNotFoundException("Template not found: " + templateId));

        notificationTemplateRepository.delete(template);
        auditService.logTemplateDeleted(request.tenantId(), templateId, template.getName());

        return ResponseEntity.noContent().build();
    }

    private NotificationTemplateDTO toDto(NotificationTemplate template) {
        return new NotificationTemplateDTO(
                template.getId(),
                template.getTenantId(),
                template.getName(),
                template.getSubject(),
                template.getBody(),
                fromJson(template.getChannels()),
                template.getIsActive(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }

    private String toJson(List<String> channels) {
        return OBJECT_MAPPER.writeValueAsString(channels);
    }

    private List<String> fromJson(String channelsJson) {
        if (channelsJson == null || channelsJson.isBlank()) {
            return List.of();
        }
        return OBJECT_MAPPER.readValue(channelsJson, new TypeReference<List<String>>() { });
    }
}
