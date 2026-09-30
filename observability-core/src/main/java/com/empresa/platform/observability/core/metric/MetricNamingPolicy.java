package com.empresa.platform.observability.core.metric;

/**
 * Standard corporate metric names and tag keys.
 */
public final class MetricNamingPolicy {

    public static final String FLOW_EXECUTIONS = "corp.flow.executions";
    public static final String FLOW_DURATION = "corp.flow.duration";
    public static final String FLOW_ERRORS = "corp.flow.errors";
    public static final String FLOW_FALLBACKS = "corp.flow.fallbacks";
    public static final String FLOW_STEP_DURATION = "corp.flow.step.duration";

    // Standard tag keys
    public static final String TAG_FLOW = "flow";
    public static final String TAG_VARIANT = "variant";
    public static final String TAG_STATUS = "status";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_ENTRYPOINT_TYPE = "entrypoint_type";
    public static final String TAG_STEP = "step";
    public static final String TAG_COMPONENT = "component";

    private MetricNamingPolicy() {}
}
