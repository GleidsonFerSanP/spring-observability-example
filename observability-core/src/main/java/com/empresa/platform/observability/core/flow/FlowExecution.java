package com.empresa.platform.observability.core.flow;

import java.util.Collection;
import java.util.Collections;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Modelo lógico e concorrente de execução de um Flow (Candidate Architecture v2 - Seção 7).
 * Seguro para acesso multi-thread durante execuções assíncronas/paralelas.
 * Suporta dimensões canônicas de baixa cardinalidade (FlowDimensions).
 */
public class FlowExecution {

    private final String flowId;
    private final String flowName;
    private String traceId;
    private String correlationId;
    private final long startNanos;
    private long endNanos;
    private String outcome = "SUCCESS";
    private final Queue<StepExecution> stepExecutions = new ConcurrentLinkedQueue<>();
    private final FlowDimensions dimensions = new FlowDimensions();

    public FlowExecution(String flowName) {
        this.flowId = UUID.randomUUID().toString();
        this.flowName = flowName;
        this.startNanos = System.nanoTime();
        this.endNanos = 0;
    }

    public FlowExecution(String flowName, long customWallClockNanos) {
        this.flowId = UUID.randomUUID().toString();
        this.flowName = flowName;
        this.startNanos = 0;
        this.endNanos = customWallClockNanos;
    }

    public void recordStep(String stepName, long durationNanos) {
        recordStep(stepName, com.empresa.platform.observability.core.annotation.ComponentType.INTERNAL, durationNanos);
    }

    public void recordStep(String stepName, com.empresa.platform.observability.core.annotation.ComponentType type, long durationNanos) {
        stepExecutions.add(new StepExecution(stepName, type, 0, durationNanos));
    }

    public void addStepExecution(StepExecution step) {
        if (step != null) {
            stepExecutions.add(step);
        }
    }

    public void complete(String outcome) {
        this.endNanos = System.nanoTime();
        if (outcome != null) {
            this.outcome = outcome;
        }
    }

    public long getWallClockDurationNanos() {
        long end = (endNanos > 0) ? endNanos : System.nanoTime();
        return Math.max(0, end - startNanos);
    }

    public long getWorkDurationNanos() {
        long sum = 0;
        for (StepExecution step : stepExecutions) {
            sum += step.getDurationNanos();
        }
        return sum;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getFlowName() {
        return flowName;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public long getStartNanos() {
        return startNanos;
    }

    public long getEndNanos() {
        return endNanos;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Collection<StepExecution> getStepExecutions() {
        return Collections.unmodifiableCollection(stepExecutions);
    }

    public FlowDimensions getDimensions() {
        return dimensions;
    }

    public void setDimension(String key, String value) {
        this.dimensions.setDimension(key, value);
    }

    public String getDimension(String key) {
        return this.dimensions.getDimension(key);
    }

    public void setVariant(String variant) {
        this.dimensions.setVariant(variant);
    }

    public String getVariant() {
        return this.dimensions.getVariant();
    }

    public void setFeature(String feature) {
        this.dimensions.setFeature(feature);
    }

    public String getFeature() {
        return this.dimensions.getFeature();
    }
}
