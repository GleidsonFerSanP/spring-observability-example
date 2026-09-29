package com.gleidsonfersanp.observability.observability.flow;

import com.gleidsonfersanp.observability.observability.alerting.AlertDispatcher;
import com.gleidsonfersanp.observability.observability.alerting.AlertEvent;
import com.gleidsonfersanp.observability.observability.alerting.AlertSeverity;
import com.gleidsonfersanp.observability.observability.alerting.AlertType;
import com.gleidsonfersanp.observability.observability.alerting.AlertingProperties;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final AlertDispatcher alertDispatcher;
    private final AlertingProperties alertingProperties;

    public FlowTrackingAspect(MeterRegistry meterRegistry, AlertDispatcher alertDispatcher, AlertingProperties alertingProperties) {
        this.meterRegistry = meterRegistry;
        this.alertDispatcher = alertDispatcher;
        this.alertingProperties = alertingProperties;
    }

    @Around("@annotation(trackFlow)")
    public Object trackFlowExecution(ProceedingJoinPoint joinPoint, TrackFlow trackFlow) throws Throwable {
        long startNanos = System.nanoTime();
        FlowContext.start(trackFlow.value());
        try {
            return joinPoint.proceed();
        } finally {
            FlowContext.complete(meterRegistry);
            long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            long flowThresholdMs = alertingProperties.getThresholdForFlow(trackFlow.value());

            if (totalDurationMs > flowThresholdMs) {
                alertDispatcher.dispatch(AlertEvent.of(
                        AlertType.FLOW_LATENCY_SLA_BREACH,
                        AlertSeverity.CRITICAL,
                        "FlowEntrypoint",
                        trackFlow.value(),
                        String.format("Duração total do fluxo '%s' (%d ms) violou o SLA end-to-end de %d ms.",
                                trackFlow.value(), totalDurationMs, flowThresholdMs),
                        totalDurationMs,
                        flowThresholdMs,
                        Map.of("flow", trackFlow.value(), "durationMs", totalDurationMs, "thresholdMs", flowThresholdMs)
                ));
            }
        }
    }

    @Around("@annotation(trackStep)")
    public Object trackStepExecution(ProceedingJoinPoint joinPoint, TrackStep trackStep) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            long duration = System.nanoTime() - startNanos;
            FlowContext.recordStep(trackStep.value(), duration);

            long stepDurationMs = TimeUnit.NANOSECONDS.toMillis(duration);
            long stepThresholdMs = alertingProperties.getThresholdForStep(trackStep.value());

            if (stepDurationMs > stepThresholdMs) {
                alertDispatcher.dispatch(AlertEvent.of(
                        AlertType.INTEGRATION_LATENCY_SLA_BREACH,
                        AlertSeverity.WARNING,
                        "FlowStep",
                        trackStep.value(),
                        String.format("Latência do subprocesso '%s' (%d ms) excedeu o SLA estipulado de %d ms.",
                                trackStep.value(), stepDurationMs, stepThresholdMs),
                        stepDurationMs,
                        stepThresholdMs,
                        Map.of("step", trackStep.value(), "durationMs", stepDurationMs, "thresholdMs", stepThresholdMs)
                ));
            }
        }
    }
}

