package com.empresa.platform.observability.autoconfigure.report;

import com.empresa.platform.observability.autoconfigure.ObservabilityProperties;
import com.empresa.platform.observability.autoconfigure.detector.TracingRuntimeDetector;
import com.empresa.platform.observability.autoconfigure.validator.ObservabilityTopologyValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Emits a single structured startup report summarizing active signals, profiles, and health.
 * (Spec Section 36)
 */
public class ObservabilityStartupReporter {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityStartupReporter.class);

    private final ObservabilityProperties properties;
    private final TracingRuntimeDetector detector;
    private final ObservabilityTopologyValidator validator;

    public ObservabilityStartupReporter(ObservabilityProperties properties,
                                        TracingRuntimeDetector detector,
                                        ObservabilityTopologyValidator validator) {
        this.properties = properties;
        this.detector = detector;
        this.validator = validator;
    }

    public void logReport(Collection<MeterRegistry> registries) {
        boolean hasDatadog = registries.stream()
                .anyMatch(r -> r.getClass().getName().contains("DatadogMeterRegistry"));
        boolean hasPrometheus = registries.stream()
                .anyMatch(r -> r.getClass().getName().contains("PrometheusMeterRegistry"));

        String profile = properties.getProfile() != null ? properties.getProfile().toUpperCase() : "DATADOG";
        String ddAgent = detector.isDatadogAgentDetected() ? "ACTIVE" : "ABSENT";
        String otelAgent = detector.isOtelAgentDetected() ? "ACTIVE" : "ABSENT";
        String validationStatus = validator.isHealthy() ? "HEALTHY" : "VIOLATIONS DETECTED";

        String report = "\n" +
                "=============================================================\n" +
                "           Corporate Observability Platform 1.0              \n" +
                "=============================================================\n" +
                "  Profile               : " + profile + "\n" +
                "  Metrics Exporters     : Datadog=" + (hasDatadog ? "ACTIVE" : "DISABLED") +
                ", Prometheus=" + (hasPrometheus ? "ACTIVE" : "DISABLED") + "\n" +
                "  Tracing Engines       : Datadog Agent=" + ddAgent + ", OTel Agent=" + otelAgent + "\n" +
                "  Features Active       : Flow=" + (properties.isFlowTrackingEnabled() ? "ON" : "OFF") +
                ", Correlation=" + (properties.isCorrelationEnabled() ? "ON" : "OFF") +
                ", Legs=" + (properties.isLegLoggingEnabled() ? "ON" : "OFF") + "\n" +
                "  Topology Health       : " + validationStatus + "\n" +
                "=============================================================";

        log.info(report);
    }
}
