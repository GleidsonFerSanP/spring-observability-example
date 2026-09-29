package com.gleidsonfersanp.observability.api;

import com.gleidsonfersanp.observability.application.UserOrchestratorService;
import com.gleidsonfersanp.observability.domain.AuditLogRepository;
import com.gleidsonfersanp.observability.domain.UserProfile;
import com.gleidsonfersanp.observability.domain.UserRegistrationRequest;
import com.gleidsonfersanp.observability.observability.alerting.AlertDispatcher;
import com.gleidsonfersanp.observability.observability.alerting.AlertEvent;
import com.gleidsonfersanp.observability.observability.flow.TrackFlow;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orchestrator")
public class UserOrchestratorController {

    private final UserOrchestratorService orchestratorService;
    private final AuditLogRepository auditLogRepository;
    private final AlertDispatcher alertDispatcher;

    public UserOrchestratorController(UserOrchestratorService orchestratorService, 
                                      AuditLogRepository auditLogRepository,
                                      AlertDispatcher alertDispatcher) {
        this.orchestratorService = orchestratorService;
        this.auditLogRepository = auditLogRepository;
        this.alertDispatcher = alertDispatcher;
    }

    // Existing sync flow
    @TrackFlow("GET /api/v1/orchestrator/users/{userId}")
    @GetMapping("/users/{userId}")
    public ResponseEntity<UserProfile> getUserProfile(@PathVariable String userId) {
        UserProfile profile = orchestratorService.fetchAndProvisionUserProfile(userId);
        return ResponseEntity.ok(profile);
    }

    // New Async Flow Entrypoint
    @TrackFlow("POST /api/v1/orchestrator/users")
    @PostMapping("/users")
    public ResponseEntity<Map<String, String>> registerUser(@RequestBody UserRegistrationRequest request) {
        orchestratorService.initiateUserRegistration(request);
        return ResponseEntity.accepted().body(Map.of("status", "ACCEPTED", "message", "User registration initiated asynchronously for " + request.userId()));
    }

    // New Endpoint to check the SQS Consumer Output
    @GetMapping("/audit")
    public ResponseEntity<List<String>> getAuditLogs() {
        return ResponseEntity.ok(auditLogRepository.getLogs());
    }

    // Endpoint para inspeção da Alarmística Reativa In-App
    @GetMapping("/alerts")
    public ResponseEntity<List<AlertEvent>> getRecentAlerts() {
        return ResponseEntity.ok(alertDispatcher.getRecentAlerts());
    }
}
