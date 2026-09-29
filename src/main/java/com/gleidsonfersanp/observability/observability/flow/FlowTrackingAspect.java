package com.gleidsonfersanp.observability.observability.flow;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(1) // Executa nas bordas antes de outros aspectos
public class FlowTrackingAspect {

    private final MeterRegistry meterRegistry;

    public FlowTrackingAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Around("@annotation(trackFlow)")
    public Object trackFlowExecution(ProceedingJoinPoint joinPoint, TrackFlow trackFlow) throws Throwable {
        FlowContext.start(trackFlow.value());
        try {
            return joinPoint.proceed();
        } finally {
            FlowContext.complete(meterRegistry);
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
        }
    }
}
