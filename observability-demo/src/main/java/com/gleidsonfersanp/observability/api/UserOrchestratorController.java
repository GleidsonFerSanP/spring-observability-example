package com.gleidsonfersanp.observability.api;

import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertEvent;
import com.empresa.platform.observability.core.annotation.LegType;
import com.empresa.platform.observability.core.annotation.LogLeg;
import com.empresa.platform.observability.core.annotation.MDC;
import com.empresa.platform.observability.core.annotation.MaskField;
import com.empresa.platform.observability.core.annotation.MaskPattern;
import com.empresa.platform.observability.core.annotation.TrackFlow;
import com.gleidsonfersanp.observability.application.UserOrchestratorService;
import com.gleidsonfersanp.observability.domain.AuditLogRepository;
import com.gleidsonfersanp.observability.domain.UserProfile;
import com.gleidsonfersanp.observability.domain.UserRegistrationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orchestrator")
public class UserOrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(UserOrchestratorController.class);

    private final UserOrchestratorService orchestratorService;
    private final AuditLogRepository auditLogRepository;
    private final AlertDispatcher alertDispatcher;

    public UserOrchestratorController(UserOrchestratorService orchestratorService,
                                      AuditLogRepository auditLogRepository,
                                      @Autowired(required = false) AlertDispatcher alertDispatcher) {
        this.orchestratorService = orchestratorService;
        this.auditLogRepository = auditLogRepository;
        this.alertDispatcher = alertDispatcher;
    }

    @TrackFlow("GET /api/v1/orchestrator/users/{userId}")
    @LogLeg(
        target = "user-orchestrator",
        type = LegType.INBOUND,
        mask = {
            @MaskField(
                expression = "#result?.customer()?.email()",
                pattern = MaskPattern.EMAIL_PARTIAL
            )
        }
    )
    @GetMapping("/users/{userId}")
    public ResponseEntity<UserProfile> getUserProfile(@PathVariable @MDC("userId") String userId) {
        log.info("Processing request in controller for user: {}", userId);
        UserProfile profile = orchestratorService.fetchAndProvisionUserProfile(userId);
        return ResponseEntity.ok(profile);
    }

    @TrackFlow("POST /api/v1/orchestrator/users")
    @LogLeg(
        target = "user-orchestrator",
        type = LegType.INBOUND,
        mask = {
            @MaskField(
                expression = "#request.email",
                pattern = MaskPattern.EMAIL_PARTIAL
            )
        }
    )
    @MDC(key = "userId", expression = "#request.userId")
    @MDC(key = "channel", value = "web")
    @PostMapping("/users")
    public ResponseEntity<Map<String, String>> registerUser(@RequestBody UserRegistrationRequest request) {
        log.info("Registering user via controller: {}", request.userId());
        orchestratorService.initiateUserRegistration(request);
        return ResponseEntity.accepted().body(Map.of("status", "ACCEPTED", "message", "User registration initiated asynchronously for " + request.userId()));
    }

    @GetMapping("/audit")
    public ResponseEntity<List<String>> getAuditLogs() {
        return ResponseEntity.ok(auditLogRepository.getLogs());
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<AlertEvent>> getRecentAlerts() {
        return ResponseEntity.ok(alertDispatcher != null ? alertDispatcher.getRecentAlerts() : List.of());
    }
}
