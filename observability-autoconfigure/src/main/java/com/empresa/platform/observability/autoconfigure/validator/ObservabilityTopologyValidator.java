package com.empresa.platform.observability.autoconfigure.validator;

import com.empresa.platform.observability.autoconfigure.ObservabilityProperties;
import com.empresa.platform.observability.autoconfigure.detector.TracingRuntimeDetector;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Validates active observability configuration, registry topology, and detects conflicts.
 * (Spec Sections 30, 31, 34, 35)
 */
public class ObservabilityTopologyValidator {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityTopologyValidator.class);

    private final ObservabilityProperties properties;
    private final TracingRuntimeDetector detector;
    private final List<String> violations = new ArrayList<>();
    private boolean healthy = true;

    public ObservabilityTopologyValidator(ObservabilityProperties properties, TracingRuntimeDetector detector) {
        this.properties = properties;
        this.detector = detector;
    }

    public void validate(Collection<MeterRegistry> registries) {
        violations.clear();
        healthy = true;

        boolean hasDatadogRegistry = registries.stream()
                .anyMatch(r -> r.getClass().getName().contains("DatadogMeterRegistry"));
        boolean hasPrometheusRegistry = registries.stream()
                .anyMatch(r -> r.getClass().getName().contains("PrometheusMeterRegistry"));

        String profile = properties.getProfile() != null ? properties.getProfile().toLowerCase() : "datadog";
        boolean allowDual = properties.getMetrics().isAllowDualExport();

        // Rule 1: Both Datadog Agent and OpenTelemetry Agent detected
        if (detector.isDatadogAgentDetected() && detector.isOtelAgentDetected()) {
            addViolation("Conflict 1 (FATAL): Both Datadog Java Agent and OpenTelemetry Java Agent detected!");
        }

        // Rule 2: Datadog Agent + OpenTelemetry SDK active
        if (detector.isDatadogAgentDetected() && detector.isOtelSdkPresent()) {
            addViolation("Conflict 2 (FATAL): Datadog Java Agent detected concurrently with OpenTelemetry SDK pipeline!");
        }

        // Rule 3: Multiple Micrometer tracing bridges
        if (detector.isMultipleTracingBridges()) {
            addViolation("Conflict 3 (FATAL): Multiple Micrometer tracing bridges detected on classpath!");
        }

        // Rule 4: Profile Datadog with active Prometheus without allowDualExport
        if ("datadog".equals(profile) && hasPrometheusRegistry && !allowDual) {
            addViolation("Conflict 4 (FATAL): Prometheus registry is ACTIVE under profile='datadog' without allow-dual-export=true!");
        }

        if (!violations.isEmpty()) {
            healthy = false;
            String mode = properties.getValidation().getMode();
            String message = "Observability Topology Validation Failed:\n - " + String.join("\n - ", violations);

            if ("fail-fast".equalsIgnoreCase(mode)) {
                log.error(message);
                throw new IllegalStateException(message);
            } else {
                log.warn(message);
            }
        }
    }

    private void addViolation(String violation) {
        violations.add(violation);
    }

    public boolean isHealthy() {
        return healthy;
    }

    public List<String> getViolations() {
        return List.copyOf(violations);
    }
}
