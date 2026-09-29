package com.gleidsonfersanp.observability.observability.flow;

import com.gleidsonfersanp.observability.observability.alerting.AlertDispatcher;
import com.gleidsonfersanp.observability.observability.alerting.AlertEvent;
import com.gleidsonfersanp.observability.observability.alerting.AlertSeverity;
import com.gleidsonfersanp.observability.observability.alerting.AlertType;
import com.gleidsonfersanp.observability.observability.alerting.AlertingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
@Order(1) // Executa nas bordas antes de outros aspectos
public class FlowTrackingAspect {

    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;
    private final AlertDispatcher alertDispatcher;
    private final AlertingProperties alertingProperties;

    public FlowTrackingAspect(MeterRegistry meterRegistry,
                              ObservationRegistry observationRegistry,
                              AlertDispatcher alertDispatcher,
                              AlertingProperties alertingProperties) {
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.alertDispatcher = alertDispatcher;
        this.alertingProperties = alertingProperties;
    }

    @Around("@annotation(trackFlow)")
    public Object trackFlowExecution(ProceedingJoinPoint joinPoint, TrackFlow trackFlow) throws Throwable {
        long startNanos = System.nanoTime();
        String flowName = trackFlow.value();

        Observation flowObservation = Observation.createNotStarted("flow." + sanitizeName(flowName), observationRegistry)
                .lowCardinalityKeyValue("flow", flowName)
                .contextualName(flowName)
                .start();

        FlowContext.start(flowName);
        try (Observation.Scope scope = flowObservation.openScope()) {
            Object result = joinPoint.proceed();
            if (FlowContext.hasInterruption()) {
                flowObservation.lowCardinalityKeyValue("flow.status", "DEGRADED_FALLBACK");
                flowObservation.highCardinalityKeyValue("flow.failed_step", FlowContext.getFailedStep());
            } else {
                flowObservation.lowCardinalityKeyValue("flow.status", "SUCCESS");
            }
            return result;
        } catch (Throwable t) {
            flowObservation.error(t);
            flowObservation.lowCardinalityKeyValue("flow.status", "INTERRUPTED");
            flowObservation.highCardinalityKeyValue("error.class", t.getClass().getName());
            flowObservation.highCardinalityKeyValue("error.message", t.getMessage() != null ? t.getMessage() : "null");
            throw t;
        } finally {
            FlowContext.complete(meterRegistry);
            flowObservation.stop();

            long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            long flowThresholdMs = alertingProperties.getThresholdForFlow(flowName);

            if (totalDurationMs > flowThresholdMs) {
                alertDispatcher.dispatch(AlertEvent.of(
                        AlertType.FLOW_LATENCY_SLA_BREACH,
                        AlertSeverity.CRITICAL,
                        "FlowEntrypoint",
                        flowName,
                        String.format("Duração total do fluxo '%s' (%d ms) violou o SLA end-to-end de %d ms.",
                                flowName, totalDurationMs, flowThresholdMs),
                        totalDurationMs,
                        flowThresholdMs,
                        Map.of("flow", flowName, "durationMs", totalDurationMs, "thresholdMs", flowThresholdMs)
                ));
            }
        }
    }

    @Around("@annotation(trackStep)")
    public Object trackStepExecution(ProceedingJoinPoint joinPoint, TrackStep trackStep) throws Throwable {
        long startNanos = System.nanoTime();
        String stepName = trackStep.value();
        String currentFlow = FlowContext.getCurrentFlowName();

        Observation stepObservation = Observation.createNotStarted("step." + sanitizeName(stepName), observationRegistry)
                .lowCardinalityKeyValue("flow", currentFlow)
                .lowCardinalityKeyValue("step", stepName)
                .contextualName(stepName)
                .start();

        try (Observation.Scope scope = stepObservation.openScope()) {
            return joinPoint.proceed();
        } catch (Throwable t) {
            stepObservation.error(t);
            stepObservation.lowCardinalityKeyValue("step.status", "FAILED");
            stepObservation.highCardinalityKeyValue("error.class", t.getClass().getName());
            stepObservation.highCardinalityKeyValue("error.message", t.getMessage() != null ? t.getMessage() : "null");

            FlowContext.recordInterruption(stepName, t, meterRegistry);

            alertDispatcher.dispatch(AlertEvent.of(
                    AlertType.FLOW_STEP_INTERRUPTION,
                    AlertSeverity.CRITICAL,
                    "FlowStep",
                    stepName,
                    String.format("Fluxo '%s' interrompido no step '%s' devido a %s: %s",
                            currentFlow, stepName, t.getClass().getSimpleName(), t.getMessage()),
                    1.0,
                    0.0,
                    Map.of(
                            "flow", currentFlow,
                            "step", stepName,
                            "errorType", t.getClass().getSimpleName(),
                            "errorMessage", t.getMessage() != null ? t.getMessage() : "null"
                    )
            ));

            throw t;
        } finally {
            long duration = System.nanoTime() - startNanos;
            FlowContext.recordStep(stepName, duration);
            stepObservation.stop();

            long stepDurationMs = TimeUnit.NANOSECONDS.toMillis(duration);
            long stepThresholdMs = alertingProperties.getThresholdForStep(stepName);

            if (stepDurationMs > stepThresholdMs) {
                alertDispatcher.dispatch(AlertEvent.of(
                        AlertType.INTEGRATION_LATENCY_SLA_BREACH,
                        AlertSeverity.WARNING,
                        "FlowStep",
                        stepName,
                        String.format("Latência do subprocesso '%s' (%d ms) excedeu o SLA estipulado de %d ms.",
                                stepName, stepDurationMs, stepThresholdMs),
                        stepDurationMs,
                        stepThresholdMs,
                        Map.of("step", stepName, "durationMs", stepDurationMs, "thresholdMs", stepThresholdMs)
                ));
            }
        }
    }

    private String sanitizeName(String value) {
        if (value == null) return "unknown";
        return value.toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.+|\\.+$", "");
    }
}

