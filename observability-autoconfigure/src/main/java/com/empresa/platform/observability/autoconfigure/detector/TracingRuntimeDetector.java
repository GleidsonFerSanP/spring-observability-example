package com.empresa.platform.observability.autoconfigure.detector;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Best-effort detector for JVM runtime javaagents, tracers, and tracing bridge configurations.
 * (Spec Sections 32, 33)
 */
public class TracingRuntimeDetector {

    public enum AgentType {
        DATADOG_AGENT,
        OTEL_AGENT,
        NONE
    }

    private final boolean datadogAgentDetected;
    private final boolean otelAgentDetected;
    private final boolean otelSdkPresent;
    private final boolean multipleTracingBridges;

    public TracingRuntimeDetector() {
        boolean ddAgent = false;
        boolean otelAgent = false;

        try {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : jvmArgs) {
                if (arg != null && arg.startsWith("-javaagent:")) {
                    if (arg.contains("dd-java-agent")) {
                        ddAgent = true;
                    }
                    if (arg.contains("opentelemetry-javaagent")) {
                        otelAgent = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        this.datadogAgentDetected = ddAgent;
        this.otelAgentDetected = otelAgent;

        // Check if OpenTelemetry SDK is present on runtime classpath
        boolean sdk = false;
        try {
            Class.forName("io.opentelemetry.sdk.trace.SdkTracerProvider");
            sdk = true;
        } catch (Throwable ignored) {
        }
        this.otelSdkPresent = sdk;

        // Check for multiple Micrometer bridges
        int bridgeCount = 0;
        try {
            Class.forName("io.micrometer.tracing.otel.bridge.OtelTracer");
            bridgeCount++;
        } catch (Throwable ignored) {}
        try {
            Class.forName("io.micrometer.tracing.brave.bridge.BraveTracer");
            bridgeCount++;
        } catch (Throwable ignored) {}
        this.multipleTracingBridges = bridgeCount > 1;
    }

    public boolean isDatadogAgentDetected() {
        return datadogAgentDetected;
    }

    public boolean isOtelAgentDetected() {
        return otelAgentDetected;
    }

    public boolean isOtelSdkPresent() {
        return otelSdkPresent;
    }

    public boolean isMultipleTracingBridges() {
        return multipleTracingBridges;
    }

    public AgentType getDetectedAgent() {
        if (datadogAgentDetected && otelAgentDetected) {
            return AgentType.DATADOG_AGENT; // Both detected (conflict will be flagged by validator)
        }
        if (datadogAgentDetected) {
            return AgentType.DATADOG_AGENT;
        }
        if (otelAgentDetected) {
            return AgentType.OTEL_AGENT;
        }
        return AgentType.NONE;
    }
}
