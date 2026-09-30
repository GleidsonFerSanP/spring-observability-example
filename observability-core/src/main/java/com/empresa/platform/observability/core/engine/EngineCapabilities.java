package com.empresa.platform.observability.core.engine;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Representa o conjunto de recursos e capacidades de uma Engine de Observabilidade.
 */
public class EngineCapabilities {

    private final String engineName;
    private final boolean supportsServiceMap;
    private final boolean supportsRequestFlowMap;
    private final boolean supportsDataStreamsMonitoring;
    private final boolean supportsNativeLatencyAttribution;
    private final boolean requiresInJvmLagPolling;
    private final Set<ObservabilityCapability> capabilities;

    public EngineCapabilities(String engineName,
                              boolean supportsServiceMap,
                              boolean supportsRequestFlowMap,
                              boolean supportsDataStreamsMonitoring,
                              boolean supportsNativeLatencyAttribution,
                              boolean requiresInJvmLagPolling) {
        this.engineName = Objects.requireNonNull(engineName, "engineName não pode ser nulo");
        this.supportsServiceMap = supportsServiceMap;
        this.supportsRequestFlowMap = supportsRequestFlowMap;
        this.supportsDataStreamsMonitoring = supportsDataStreamsMonitoring;
        this.supportsNativeLatencyAttribution = supportsNativeLatencyAttribution;
        this.requiresInJvmLagPolling = requiresInJvmLagPolling;

        Set<ObservabilityCapability> caps = new HashSet<>();
        if (supportsServiceMap) caps.add(ObservabilityCapability.SERVICE_MAP);
        if (supportsRequestFlowMap) caps.add(ObservabilityCapability.REQUEST_FLOW_MAP);
        if (supportsDataStreamsMonitoring) caps.add(ObservabilityCapability.DATA_STREAMS_MONITORING);
        if (supportsNativeLatencyAttribution) caps.add(ObservabilityCapability.NATIVE_LATENCY_ATTRIBUTION);
        if (requiresInJvmLagPolling) caps.add(ObservabilityCapability.IN_JVM_LAG_POLLING);
        caps.add(ObservabilityCapability.CUSTOM_SPAN_TAGS);
        this.capabilities = Collections.unmodifiableSet(caps);
    }

    public static EngineCapabilities datadog() {
        return new EngineCapabilities("datadog", true, true, true, true, false);
    }

    public static EngineCapabilities micrometer() {
        return new EngineCapabilities("micrometer", false, false, false, false, true);
    }

    public static EngineCapabilities openTelemetry() {
        return new EngineCapabilities("opentelemetry", true, true, false, false, true);
    }

    public String getEngineName() {
        return engineName;
    }

    public boolean supportsServiceMap() {
        return supportsServiceMap;
    }

    public boolean supportsRequestFlowMap() {
        return supportsRequestFlowMap;
    }

    public boolean supportsDataStreamsMonitoring() {
        return supportsDataStreamsMonitoring;
    }

    public boolean supportsNativeLatencyAttribution() {
        return supportsNativeLatencyAttribution;
    }

    public boolean requiresInJvmLagPolling() {
        return requiresInJvmLagPolling;
    }

    public boolean hasCapability(ObservabilityCapability capability) {
        return capabilities.contains(capability);
    }

    public Set<ObservabilityCapability> getCapabilities() {
        return capabilities;
    }
}
