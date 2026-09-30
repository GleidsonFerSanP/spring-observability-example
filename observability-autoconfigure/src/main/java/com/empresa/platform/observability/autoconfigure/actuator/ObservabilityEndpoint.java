package com.empresa.platform.observability.autoconfigure.actuator;

import com.empresa.platform.observability.autoconfigure.ObservabilityProperties;
import com.empresa.platform.observability.autoconfigure.detector.TracingRuntimeDetector;
import com.empresa.platform.observability.autoconfigure.validator.ObservabilityTopologyValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostics actuator endpoint: /actuator/observability
 * (Spec Section 37)
 */
@Endpoint(id = "observability")
public class ObservabilityEndpoint {

    private final ObservabilityProperties properties;
    private final TracingRuntimeDetector detector;
    private final ObservabilityTopologyValidator validator;
    private final Collection<MeterRegistry> registries;

    public ObservabilityEndpoint(ObservabilityProperties properties,
                                 TracingRuntimeDetector detector,
                                 ObservabilityTopologyValidator validator,
                                 Collection<MeterRegistry> registries) {
        this.properties = properties;
        this.detector = detector;
        this.validator = validator;
        this.registries = registries;
    }

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", properties.getProfile());
        result.put("status", validator.isHealthy() ? "HEALTHY" : "DEGRADED");

        Map<String, String> metrics = new LinkedHashMap<>();
        boolean ddActive = registries.stream().anyMatch(r -> r.getClass().getName().contains("DatadogMeterRegistry"));
        boolean promActive = registries.stream().anyMatch(r -> r.getClass().getName().contains("PrometheusMeterRegistry"));
        metrics.put("datadog", ddActive ? "ACTIVE" : "DISABLED");
        metrics.put("prometheus", promActive ? "ACTIVE" : "DISABLED");
        metrics.put("allowDualExport", String.valueOf(properties.getMetrics().isAllowDualExport()));
        result.put("metrics", metrics);

        Map<String, Object> tracing = new LinkedHashMap<>();
        tracing.put("engine", detector.getDetectedAgent().name());
        tracing.put("datadogAgent", detector.isDatadogAgentDetected());
        tracing.put("otelAgent", detector.isOtelAgentDetected());
        tracing.put("duplicateDetected", !validator.isHealthy());
        result.put("tracing", tracing);

        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("flow", properties.isFlowTrackingEnabled());
        capabilities.put("featureVariant", true);
        capabilities.put("correlation", properties.isCorrelationEnabled());
        capabilities.put("legs", properties.isLegLoggingEnabled());
        capabilities.put("resilience", properties.isResilienceEnabled());
        result.put("capabilities", capabilities);

        return result;
    }
}
