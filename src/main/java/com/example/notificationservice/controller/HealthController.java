package com.example.notificationservice.controller;

import com.example.notificationservice.dto.HealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        log.debug("Received GET request to /api/health");
        return ResponseEntity.ok(new HealthResponse("UP", Instant.now()));
    }
}
