package com.empresa.platform.observability.core.flow;

import com.empresa.platform.observability.core.annotation.ComponentType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modelo lógico de execução temporal de uma etapa do fluxo (Candidate Architecture v2 - Seção 7).
 */
public class StepExecution {

    private final String stepId;
    private final String parentStepId;
    private final String component;
    private final ComponentType componentType;
    private final long startNanos;
    private long endNanos;
    private String outcome = "SUCCESS";
    private boolean asynchronous;
    private int retryAttempt;
    private final Map<String, String> attributes = new ConcurrentHashMap<>();

    public StepExecution(String component, long durationNanos) {
        this(component, ComponentType.INTERNAL, 0, durationNanos);
    }

    public StepExecution(String component, ComponentType componentType, long startNanos) {
        this(UUID.randomUUID().toString(), null, component, componentType, startNanos);
    }

    public StepExecution(String component, ComponentType componentType, long startNanos, long endNanos) {
        this.stepId = UUID.randomUUID().toString();
        this.parentStepId = null;
        this.component = component;
        this.componentType = componentType != null ? componentType : ComponentType.INTERNAL;
        this.startNanos = startNanos;
        this.endNanos = endNanos;
    }

    public StepExecution(String stepId, String parentStepId, String component, ComponentType componentType, long startNanos) {
        this.stepId = stepId;
        this.parentStepId = parentStepId;
        this.component = component;
        this.componentType = componentType != null ? componentType : ComponentType.INTERNAL;
        this.startNanos = startNanos;
        this.endNanos = startNanos;
    }

    public String getStepName() {
        return component;
    }

    public String getType() {
        return componentType != null ? componentType.name() : "INTERNAL";
    }

    public void complete(long endNanos, String outcome) {
        this.endNanos = endNanos;
        if (outcome != null) {
            this.outcome = outcome;
        }
    }

    public long getDurationNanos() {
        return Math.max(0, endNanos - startNanos);
    }

    public String getStepId() {
        return stepId;
    }

    public String getParentStepId() {
        return parentStepId;
    }

    public String getComponent() {
        return component;
    }

    public ComponentType getComponentType() {
        return componentType;
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

    public boolean isAsynchronous() {
        return asynchronous;
    }

    public void setAsynchronous(boolean asynchronous) {
        this.asynchronous = asynchronous;
    }

    public int getRetryAttempt() {
        return retryAttempt;
    }

    public void setRetryAttempt(int retryAttempt) {
        this.retryAttempt = retryAttempt;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
