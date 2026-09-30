package com.empresa.platform.observability.core.flow;

import com.empresa.platform.observability.core.annotation.BusinessOutcome;
import com.empresa.platform.observability.core.annotation.FlowStatus;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic context for an active business flow execution.
 * Decoupled from temporal execution accumulation (wall-clock timeline is delegated to tracing).
 */
public final class FlowSemanticContext {

    private static final ThreadLocal<FlowSemanticContext> CURRENT = new ThreadLocal<>();

    private final String flowName;
    private final String flowType;
    private String variant;
    private FlowStatus status = FlowStatus.SUCCESS;
    private String businessOutcome = BusinessOutcome.COMPLETED;
    private final Map<String, String> lowCardinalityDimensions = new ConcurrentHashMap<>();

    public FlowSemanticContext(String flowName, String flowType, String variant) {
        this.flowName = flowName != null ? flowName : "unknown-flow";
        this.flowType = flowType != null ? flowType : "HTTP";
        this.variant = variant != null && !variant.isBlank() ? variant : "default";
    }

    public static FlowSemanticContext current() {
        return CURRENT.get();
    }

    public static void set(FlowSemanticContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public String getFlowName() {
        return flowName;
    }

    public String getFlowType() {
        return flowType;
    }

    public String getVariant() {
        return variant;
    }

    public void setVariant(String variant) {
        if (variant != null && !variant.isBlank()) {
            this.variant = variant;
        }
    }

    public FlowStatus getStatus() {
        return status;
    }

    public void setStatus(FlowStatus status) {
        if (status != null) {
            this.status = status;
        }
    }

    public String getBusinessOutcome() {
        return businessOutcome;
    }

    public void setBusinessOutcome(String businessOutcome) {
        if (businessOutcome != null) {
            this.businessOutcome = businessOutcome;
        }
    }

    public void putDimension(String key, String value) {
        if (key != null && value != null) {
            this.lowCardinalityDimensions.put(key, value);
        }
    }

    public Map<String, String> getLowCardinalityDimensions() {
        return Collections.unmodifiableMap(lowCardinalityDimensions);
    }
}
