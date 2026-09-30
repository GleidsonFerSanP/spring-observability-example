package com.empresa.platform.observability.core.engine;

import com.empresa.platform.observability.core.flow.FlowContext;
import com.empresa.platform.observability.core.flow.FlowDimensions;
import com.empresa.platform.observability.core.flow.FlowExecution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.Map;

/**
 * Adaptador de Referência e Portabilidade da Engine de Observabilidade.
 * Utiliza Micrometer Observation e MeterRegistry diretamente, sendo ideal para
 * ambientes locais, esteiras de CI e ecossistemas open-source baseados em Prometheus, Jaeger e Grafana.
 */
public class MicrometerObservabilityEngine implements ObservabilityEngine {

    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final EngineCapabilities capabilities;

    public MicrometerObservabilityEngine(ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
        this.meterRegistry = meterRegistry;
        this.capabilities = EngineCapabilities.micrometer();
    }

    @Override
    public EngineCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public FlowScope startFlow(String flowName, String flowType, FlowDimensions dimensions) {
        Observation observation = Observation.createNotStarted("flow." + sanitizeName(flowName), observationRegistry)
                .lowCardinalityKeyValue("flow", flowName)
                .lowCardinalityKeyValue("flow.type", flowType != null ? flowType : "UNKNOWN")
                .contextualName(flowName);

        if (dimensions != null) {
            for (Map.Entry<String, String> entry : dimensions.asMap().entrySet()) {
                FlowContext.setDimension(entry.getKey(), entry.getValue());
            }
            if (dimensions.getVariant() != null) {
                observation.lowCardinalityKeyValue(FlowDimensions.VARIANT_KEY, dimensions.getVariant());
            }
            if (dimensions.getFeature() != null) {
                observation.lowCardinalityKeyValue(FlowDimensions.FEATURE_KEY, dimensions.getFeature());
            }
            if (dimensions.getExperiment() != null) {
                observation.lowCardinalityKeyValue(FlowDimensions.EXPERIMENT_KEY, dimensions.getExperiment());
            }
        }

        observation.start();
        Observation.Scope scope = observation.openScope();
        return new MicrometerFlowScope(observation, scope);
    }

    @Override
    public void completeFlow(FlowExecution execution, FlowScope scope) {
        if (execution != null && scope instanceof MicrometerFlowScope mScope) {
            for (Map.Entry<String, String> entry : execution.getDimensions().asMap().entrySet()) {
                mScope.observation.lowCardinalityKeyValue(entry.getKey(), entry.getValue());
            }
        }
        if (scope != null) {
            scope.close();
        }
        FlowContext.complete(meterRegistry);
    }

    @Override
    public void recordFlowInterruption(String flowName, String stepName, Throwable error, FlowDimensions dimensions, FlowScope scope) {
        if (scope instanceof MicrometerFlowScope mScope) {
            mScope.markInterrupted(stepName, error);
        }
    }

    @Override
    public StepScope startStep(String flowName, String stepName, String stepType, FlowDimensions dimensions) {
        Observation observation = Observation.createNotStarted("step." + sanitizeName(stepName), observationRegistry)
                .lowCardinalityKeyValue("flow", flowName != null ? flowName : "unknown")
                .lowCardinalityKeyValue("step", stepName)
                .lowCardinalityKeyValue("step.type", stepType != null ? stepType : "INTERNAL")
                .contextualName(stepName);

        if (dimensions != null) {
            for (Map.Entry<String, String> entry : dimensions.asMap().entrySet()) {
                FlowContext.setDimension(entry.getKey(), entry.getValue());
            }
            if (dimensions.getVariant() != null) {
                observation.lowCardinalityKeyValue(FlowDimensions.VARIANT_KEY, dimensions.getVariant());
            }
        }

        observation.start();
        Observation.Scope scope = observation.openScope();
        return new MicrometerStepScope(observation, scope);
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
        if (scope instanceof MicrometerStepScope mScope) {
            mScope.markFailed(error);
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
    }

    private String sanitizeName(String value) {
        if (value == null) return "unknown";
        return value.toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
    }

    private static class MicrometerFlowScope implements FlowScope {
        final Observation observation;
        final Observation.Scope scope;
        private boolean closed = false;

        MicrometerFlowScope(Observation observation, Observation.Scope scope) {
            this.observation = observation;
            this.scope = scope;
        }

        @Override
        public void tag(String key, String value) {
            if (key != null && value != null) {
                observation.lowCardinalityKeyValue(key, value);
            }
        }

        @Override
        public void markSuccess() {
            observation.lowCardinalityKeyValue("flow.status", "SUCCESS");
        }

        @Override
        public void markDegraded(String failedStep) {
            observation.lowCardinalityKeyValue("flow.status", "DEGRADED_FALLBACK");
            if (failedStep != null) {
                observation.highCardinalityKeyValue("flow.failed_step", failedStep);
            }
        }

        @Override
        public void markInterrupted(String failedStep, Throwable error) {
            observation.lowCardinalityKeyValue("flow.status", "INTERRUPTED");
            if (failedStep != null) {
                observation.highCardinalityKeyValue("flow.failed_step", failedStep);
            }
            if (error != null) {
                observation.error(error);
                observation.highCardinalityKeyValue("error.class", error.getClass().getName());
                observation.highCardinalityKeyValue("error.message", error.getMessage() != null ? error.getMessage() : "null");
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

    private static class MicrometerStepScope implements StepScope {
        final Observation observation;
        final Observation.Scope scope;
        private boolean closed = false;

        MicrometerStepScope(Observation observation, Observation.Scope scope) {
            this.observation = observation;
            this.scope = scope;
        }

        @Override
        public void tag(String key, String value) {
            if (key != null && value != null) {
                observation.lowCardinalityKeyValue(key, value);
            }
        }

        @Override
        public void markFailed(Throwable error) {
            observation.lowCardinalityKeyValue("step.status", "FAILED");
            if (error != null) {
                observation.error(error);
                observation.highCardinalityKeyValue("error.class", error.getClass().getName());
                observation.highCardinalityKeyValue("error.message", error.getMessage() != null ? error.getMessage() : "null");
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
