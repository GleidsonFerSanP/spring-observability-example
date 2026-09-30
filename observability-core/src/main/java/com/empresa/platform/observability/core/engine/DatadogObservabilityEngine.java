package com.empresa.platform.observability.core.engine;

import com.empresa.platform.observability.core.flow.FlowContext;
import com.empresa.platform.observability.core.flow.FlowDimensions;
import com.empresa.platform.observability.core.flow.FlowExecution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Map;

/**
 * Adaptador Oficial Corporativo da Engine de Observabilidade para Datadog.
 * <p>
 * Opera em conjunto com o Datadog APM e o {@code dd-java-agent} utilizando padrões abertos
 * (Micrometer Observation e ponte OpenTelemetry API via {@code DD_TRACE_OTEL_ENABLED=true}).
 * <p>
 * Características Principais:
 * <ul>
 *   <li>Injeta tags semânticas padronizadas ({@code flow.name}, {@code flow.variant}, {@code flow.step}, {@code flow.status}, {@code feature.name}) que alimentam o Datadog Request Flow Map e Trace Explorer.</li>
 *   <li>Reporta {@code requiresInJvmLagPolling() == false}, permitindo suprimir polling periódico de lag in-JVM em favor do Datadog Data Streams Monitoring (DSM).</li>
 *   <li>Zero dependência de bibliotecas fechadas ou proprietárias no classpath de compilação da aplicação.</li>
 * </ul>
 */
public class DatadogObservabilityEngine implements ObservabilityEngine {

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final EngineCapabilities capabilities;

    public DatadogObservabilityEngine(ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
        this.meterRegistry = meterRegistry;
        this.capabilities = EngineCapabilities.datadog();
    }

    @Override
    public EngineCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public FlowScope startFlow(String flowName, String flowType, FlowDimensions dimensions) {
        Observation observation = Observation.createNotStarted("flow." + sanitizeName(flowName), observationRegistry)
                .lowCardinalityKeyValue("flow.name", flowName)
                .lowCardinalityKeyValue("flow", flowName)
                .lowCardinalityKeyValue("flow.type", flowType != null ? flowType : "UNKNOWN")
                .contextualName(flowName);

        OtelSpanBridge.setAttribute("flow.name", flowName);
        if (flowType != null) {
            OtelSpanBridge.setAttribute("flow.type", flowType);
        }

        if (dimensions != null) {
            for (Map.Entry<String, String> entry : dimensions.asMap().entrySet()) {
                FlowContext.setDimension(entry.getKey(), entry.getValue());
            }
            if (dimensions.getVariant() != null) {
                observation.lowCardinalityKeyValue("flow.variant", dimensions.getVariant());
                observation.lowCardinalityKeyValue(FlowDimensions.VARIANT_KEY, dimensions.getVariant());
                OtelSpanBridge.setAttribute("flow.variant", dimensions.getVariant());
            }
            if (dimensions.getFeature() != null) {
                observation.lowCardinalityKeyValue("feature.name", dimensions.getFeature());
                OtelSpanBridge.setAttribute("feature.name", dimensions.getFeature());
                if (dimensions.getVariant() != null) {
                    observation.lowCardinalityKeyValue("feature.variant", dimensions.getVariant());
                    OtelSpanBridge.setAttribute("feature.variant", dimensions.getVariant());
                }
            }
            if (dimensions.getExperiment() != null) {
                observation.lowCardinalityKeyValue("flow.experiment", dimensions.getExperiment());
                OtelSpanBridge.setAttribute("flow.experiment", dimensions.getExperiment());
            }
        }

        observation.start();
        Observation.Scope scope = observation.openScope();
        return new DatadogFlowScope(observation, scope);
    }

    @Override
    public void completeFlow(FlowExecution execution, FlowScope scope) {
        if (execution != null && scope instanceof DatadogFlowScope dScope) {
            for (Map.Entry<String, String> entry : execution.getDimensions().asMap().entrySet()) {
                dScope.observation.lowCardinalityKeyValue(entry.getKey(), entry.getValue());
                OtelSpanBridge.setAttribute(entry.getKey(), entry.getValue());
            }
            if (execution.getDimensions().getVariant() != null) {
                dScope.observation.lowCardinalityKeyValue("flow.variant", execution.getDimensions().getVariant());
                OtelSpanBridge.setAttribute("flow.variant", execution.getDimensions().getVariant());
            }
        }
        if (scope != null) {
            scope.close();
        }
        FlowContext.complete(meterRegistry);
    }

    @Override
    public void recordFlowInterruption(String flowName, String stepName, Throwable error, FlowDimensions dimensions, FlowScope scope) {
        if (scope instanceof DatadogFlowScope dScope) {
            dScope.markInterrupted(stepName, error);
        }
    }

    @Override
    public StepScope startStep(String flowName, String stepName, String stepType, FlowDimensions dimensions) {
        Observation observation = Observation.createNotStarted("step." + sanitizeName(stepName), observationRegistry)
                .lowCardinalityKeyValue("flow.name", flowName != null ? flowName : "unknown")
                .lowCardinalityKeyValue("flow", flowName != null ? flowName : "unknown")
                .lowCardinalityKeyValue("flow.step", stepName)
                .lowCardinalityKeyValue("step", stepName)
                .lowCardinalityKeyValue("step.type", stepType != null ? stepType : "INTERNAL")
                .contextualName(stepName);

        OtelSpanBridge.setAttribute("flow.name", flowName != null ? flowName : "unknown");
        OtelSpanBridge.setAttribute("flow.step", stepName);
        if (stepType != null) {
            OtelSpanBridge.setAttribute("step.type", stepType);
        }

        if (dimensions != null) {
            for (Map.Entry<String, String> entry : dimensions.asMap().entrySet()) {
                FlowContext.setDimension(entry.getKey(), entry.getValue());
            }
            if (dimensions.getVariant() != null) {
                observation.lowCardinalityKeyValue("flow.variant", dimensions.getVariant());
                observation.lowCardinalityKeyValue(FlowDimensions.VARIANT_KEY, dimensions.getVariant());
                OtelSpanBridge.setAttribute("flow.variant", dimensions.getVariant());
            }
        }

        observation.start();
        Observation.Scope scope = observation.openScope();
        return new DatadogStepScope(observation, scope);
    }

    @Override
    public void completeStep(String flowName, String stepName, String stepType, long durationNanos, FlowDimensions dimensions, StepScope scope) {
        FlowContext.recordStep(stepName, stepType, durationNanos);
        if (scope != null) {
            scope.close();
        }
    }

    @Override
    public void recordStepInterruption(String flowName, String stepName, Throwable error, FlowDimensions dimensions, StepScope scope) {
        if (scope instanceof DatadogStepScope dScope) {
            dScope.markFailed(error);
        }
        FlowContext.recordInterruption(stepName, error, meterRegistry);
    }

    @Override
    public void tagAttribute(String key, String value) {
        if (key == null || value == null) return;
        Observation current = observationRegistry.getCurrentObservation();
        if (current != null) {
            current.lowCardinalityKeyValue(key, value);
        }
        OtelSpanBridge.setAttribute(key, value);
    }

    private String sanitizeName(String value) {
        if (value == null) return "unknown";
        return value.toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
    }

    private static class DatadogFlowScope implements FlowScope {
        final Observation observation;
        final Observation.Scope scope;
        private boolean closed = false;

        DatadogFlowScope(Observation observation, Observation.Scope scope) {
            this.observation = observation;
            this.scope = scope;
        }

        @Override
        public void tag(String key, String value) {
            if (key != null && value != null) {
                observation.lowCardinalityKeyValue(key, value);
                OtelSpanBridge.setAttribute(key, value);
            }
        }

        @Override
        public void markSuccess() {
            observation.lowCardinalityKeyValue("flow.status", "SUCCESS");
            OtelSpanBridge.setAttribute("flow.status", "SUCCESS");
        }

        @Override
        public void markDegraded(String failedStep) {
            observation.lowCardinalityKeyValue("flow.status", "DEGRADED_FALLBACK");
            OtelSpanBridge.setAttribute("flow.status", "DEGRADED_FALLBACK");
            if (failedStep != null) {
                observation.highCardinalityKeyValue("flow.failed_step", failedStep);
                OtelSpanBridge.setAttribute("flow.failed_step", failedStep);
            }
        }

        @Override
        public void markInterrupted(String failedStep, Throwable error) {
            observation.lowCardinalityKeyValue("flow.status", "INTERRUPTED");
            OtelSpanBridge.setAttribute("flow.status", "INTERRUPTED");
            if (failedStep != null) {
                observation.highCardinalityKeyValue("flow.failed_step", failedStep);
                OtelSpanBridge.setAttribute("flow.failed_step", failedStep);
            }
            if (error != null) {
                observation.error(error);
                observation.highCardinalityKeyValue("error.class", error.getClass().getName());
                observation.highCardinalityKeyValue("error.message", error.getMessage() != null ? error.getMessage() : "null");
                OtelSpanBridge.setAttribute("error.type", error.getClass().getSimpleName());
                OtelSpanBridge.setAttribute("error.message", error.getMessage() != null ? error.getMessage() : "null");
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (scope != null) {
                    scope.close();
                }
                observation.stop();
            }
        }
    }

    private static class DatadogStepScope implements StepScope {
        final Observation observation;
        final Observation.Scope scope;
        private boolean closed = false;

        DatadogStepScope(Observation observation, Observation.Scope scope) {
            this.observation = observation;
            this.scope = scope;
        }

        @Override
        public void tag(String key, String value) {
            if (key != null && value != null) {
                observation.lowCardinalityKeyValue(key, value);
                OtelSpanBridge.setAttribute(key, value);
            }
        }

        @Override
        public void markFailed(Throwable error) {
            observation.lowCardinalityKeyValue("step.status", "FAILED");
            OtelSpanBridge.setAttribute("step.status", "FAILED");
            if (error != null) {
                observation.error(error);
                observation.highCardinalityKeyValue("error.class", error.getClass().getName());
                observation.highCardinalityKeyValue("error.message", error.getMessage() != null ? error.getMessage() : "null");
                OtelSpanBridge.setAttribute("error.type", error.getClass().getSimpleName());
                OtelSpanBridge.setAttribute("error.message", error.getMessage() != null ? error.getMessage() : "null");
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (scope != null) {
                    scope.close();
                }
                observation.stop();
            }
        }
    }
}
