package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.core.alerting.AlertingProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    /**
     * Master switch for the corporate observability starter. Default is true.
     */
    private boolean enabled = true;

    /**
     * Active profile: "datadog" (default), "prometheus", or "custom".
     */
    private String profile = "datadog";

    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();

    @NestedConfigurationProperty
    private TracingProperties tracing = new TracingProperties();

    @NestedConfigurationProperty
    private LogsProperties logs = new LogsProperties();

    @NestedConfigurationProperty
    private FlowProperties flow = new FlowProperties();

    @NestedConfigurationProperty
    private CorrelationProperties correlation = new CorrelationProperties();

    @NestedConfigurationProperty
    private ValidationProperties validation = new ValidationProperties();

    @NestedConfigurationProperty
    private InfrastructureProperties infrastructure = new InfrastructureProperties();

    @NestedConfigurationProperty
    private AlertingProperties alerting = new AlertingProperties();

    // Legacy / Convenience flags
    private boolean flowTrackingEnabled = true;
    private boolean legLoggingEnabled = true;
    private boolean spelObservationEnabled = true;
    private boolean correlationEnabled = true;
    private boolean alertingEnabled = false; // Deprecated as default (Spec 44)
    private boolean asyncDecoratorEnabled = true;
    private boolean observationHandlerEnabled = false; // Disabled by default (Spec 47)
    private boolean feignEnabled = true;
    private boolean resilienceEnabled = true;
    private boolean jdbcEnabled = true;
    private String engine = "datadog"; // Default datadog (Spec 4)

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public MetricsProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsProperties metrics) {
        this.metrics = metrics;
    }

    public TracingProperties getTracing() {
        return tracing;
    }

    public void setTracing(TracingProperties tracing) {
        this.tracing = tracing;
    }

    public LogsProperties getLogs() {
        return logs;
    }

    public void setLogs(LogsProperties logs) {
        this.logs = logs;
    }

    public FlowProperties getFlow() {
        return flow;
    }

    public void setFlow(FlowProperties flow) {
        this.flow = flow;
    }

    public CorrelationProperties getCorrelation() {
        return correlation;
    }

    public void setCorrelation(CorrelationProperties correlation) {
        this.correlation = correlation;
    }

    public ValidationProperties getValidation() {
        return validation;
    }

    public void setValidation(ValidationProperties validation) {
        this.validation = validation;
    }

    public InfrastructureProperties getInfrastructure() {
        return infrastructure;
    }

    public void setInfrastructure(InfrastructureProperties infrastructure) {
        this.infrastructure = infrastructure;
    }

    public AlertingProperties getAlerting() {
        return alerting;
    }

    public void setAlerting(AlertingProperties alerting) {
        this.alerting = alerting;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public boolean isFlowTrackingEnabled() {
        return flowTrackingEnabled && flow.isEnabled();
    }

    public void setFlowTrackingEnabled(boolean flowTrackingEnabled) {
        this.flowTrackingEnabled = flowTrackingEnabled;
        this.flow.setEnabled(flowTrackingEnabled);
    }

    public boolean isLegLoggingEnabled() {
        return legLoggingEnabled;
    }

    public void setLegLoggingEnabled(boolean legLoggingEnabled) {
        this.legLoggingEnabled = legLoggingEnabled;
    }

    public boolean isSpelObservationEnabled() {
        return spelObservationEnabled;
    }

    public void setSpelObservationEnabled(boolean spelObservationEnabled) {
        this.spelObservationEnabled = spelObservationEnabled;
    }

    public boolean isCorrelationEnabled() {
        return correlationEnabled && correlation.isEnabled();
    }

    public void setCorrelationEnabled(boolean correlationEnabled) {
        this.correlationEnabled = correlationEnabled;
        this.correlation.setEnabled(correlationEnabled);
    }

    public boolean isAlertingEnabled() {
        return alertingEnabled;
    }

    public void setAlertingEnabled(boolean alertingEnabled) {
        this.alertingEnabled = alertingEnabled;
    }

    public boolean isAsyncDecoratorEnabled() {
        return asyncDecoratorEnabled;
    }

    public void setAsyncDecoratorEnabled(boolean asyncDecoratorEnabled) {
        this.asyncDecoratorEnabled = asyncDecoratorEnabled;
    }

    public boolean isObservationHandlerEnabled() {
        return observationHandlerEnabled;
    }

    public void setObservationHandlerEnabled(boolean observationHandlerEnabled) {
        this.observationHandlerEnabled = observationHandlerEnabled;
    }

    public boolean isFeignEnabled() {
        return feignEnabled;
    }

    public void setFeignEnabled(boolean feignEnabled) {
        this.feignEnabled = feignEnabled;
    }

    public boolean isResilienceEnabled() {
        return resilienceEnabled;
    }

    public void setResilienceEnabled(boolean resilienceEnabled) {
        this.resilienceEnabled = resilienceEnabled;
    }

    public boolean isJdbcEnabled() {
        return jdbcEnabled;
    }

    public void setJdbcEnabled(boolean jdbcEnabled) {
        this.jdbcEnabled = jdbcEnabled;
    }

    // Nested Properties Classes
    public static class MetricsProperties {
        private List<String> exporters = new ArrayList<>(List.of("datadog"));
        private boolean allowDualExport = false;

        public List<String> getExporters() {
            return exporters;
        }

        public void setExporters(List<String> exporters) {
            this.exporters = exporters;
        }

        public boolean isAllowDualExport() {
            return allowDualExport;
        }

        public void setAllowDualExport(boolean allowDualExport) {
            this.allowDualExport = allowDualExport;
        }
    }

    public static class TracingProperties {
        private String engine = "auto"; // auto | datadog-agent | otel-agent | none
        private String duplicatePolicy = "fail"; // fail | warn

        public String getEngine() {
            return engine;
        }

        public void setEngine(String engine) {
            this.engine = engine;
        }

        public String getDuplicatePolicy() {
            return duplicatePolicy;
        }

        public void setDuplicatePolicy(String duplicatePolicy) {
            this.duplicatePolicy = duplicatePolicy;
        }
    }

    public static class LogsProperties {
        private boolean structured = true;
        private boolean payload = false;

        public boolean isStructured() {
            return structured;
        }

        public void setStructured(boolean structured) {
            this.structured = structured;
        }

        public boolean isPayload() {
            return payload;
        }

        public void setPayload(boolean payload) {
            this.payload = payload;
        }
    }

    public static class FlowProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class CorrelationProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ValidationProperties {
        private String mode = "fail-fast"; // fail-fast | warn

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    public static class InfrastructureProperties {
        @NestedConfigurationProperty
        private KafkaLagProperties kafkaLag = new KafkaLagProperties();

        @NestedConfigurationProperty
        private SqsPollingProperties sqsPolling = new SqsPollingProperties();

        public KafkaLagProperties getKafkaLag() {
            return kafkaLag;
        }

        public void setKafkaLag(KafkaLagProperties kafkaLag) {
            this.kafkaLag = kafkaLag;
        }

        public SqsPollingProperties getSqsPolling() {
            return sqsPolling;
        }

        public void setSqsPolling(SqsPollingProperties sqsPolling) {
            this.sqsPolling = sqsPolling;
        }
    }

    public static class KafkaLagProperties {
        private boolean enabled = false; // Disabled by default (Spec 45)

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class SqsPollingProperties {
        private boolean enabled = false; // Disabled by default (Spec 46)

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
