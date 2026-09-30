package com.empresa.platform.observability.core.flow;

import com.empresa.platform.observability.core.annotation.ComponentType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Contexto de execução do Flow (Candidate Architecture v2 - Seções 3, 7, 8, 37).
 * Integra o modelo lógico FlowExecution com segurança para execuções concorrentes,
 * suporte a dimensões canônicas (FlowDimensions) e cálculo das três dimensões temporais
 * fundamentais via LatencyAttributionEngine.
 */
public class FlowContext {

    private static final ThreadLocal<Deque<FlowContext>> CURRENT_FLOW = ThreadLocal.withInitial(ArrayDeque::new);

    private final String flowName;
    private final long startNanos;
    private final FlowExecution execution;
    private final Map<String, Long> stepDurations = new ConcurrentHashMap<>();
    private final Map<String, String> stepTypes = new ConcurrentHashMap<>();
    private String failedStep;
    private Throwable failureError;

    private FlowContext(String flowName) {
        this.flowName = flowName;
        this.startNanos = System.nanoTime();
        this.execution = new FlowExecution(flowName);
    }

    public static void start(String flowName) {
        CURRENT_FLOW.get().push(new FlowContext(flowName));
    }

    public static String getCurrentFlowName() {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        return stack.isEmpty() ? "unknown" : stack.peek().flowName;
    }

    public static FlowExecution getCurrentExecution() {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        return stack.isEmpty() ? null : stack.peek().execution;
    }

    public static void setDimension(String key, String value) {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        if (!stack.isEmpty()) {
            stack.peek().execution.setDimension(key, value);
        }
    }

    public static void setVariant(String variant) {
        setDimension(FlowDimensions.VARIANT_KEY, variant);
    }

    public static void setFeature(String feature) {
        setDimension(FlowDimensions.FEATURE_KEY, feature);
    }

    public static String getDimension(String key) {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        return !stack.isEmpty() ? stack.peek().execution.getDimension(key) : null;
    }

    public static FlowDimensions getCurrentDimensions() {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        return !stack.isEmpty() ? stack.peek().execution.getDimensions() : new FlowDimensions();
    }

    public static void recordStep(String stepName, long durationNanos) {
        recordStep(stepName, "INTERNAL", durationNanos);
    }

    public static void recordStep(String stepName, String type, long durationNanos) {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        if (!stack.isEmpty()) {
            FlowContext current = stack.peek();
            current.stepDurations.merge(stepName, durationNanos, Long::sum);
            current.stepTypes.putIfAbsent(stepName, type != null ? type : "INTERNAL");

            // Registra StepExecution no modelo temporal
            long end = System.nanoTime();
            long start = end - durationNanos;
            ComponentType cType = ComponentType.INTERNAL;
            try {
                if (type != null) {
                    cType = ComponentType.valueOf(type.toUpperCase());
                }
            } catch (Exception ignored) {
            }

            StepExecution stepExec = new StepExecution(stepName, cType, start);
            stepExec.complete(end, "SUCCESS");
            current.execution.addStepExecution(stepExec);
        }
    }

    public static void recordInterruption(String stepName, Throwable t, MeterRegistry registry) {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        String currentFlow = "unknown";
        Tags dimensionTags = Tags.empty();
        if (!stack.isEmpty()) {
            FlowContext current = stack.peek();
            current.failedStep = stepName;
            current.failureError = t;
            current.execution.setOutcome("INTERRUPTED");
            currentFlow = current.flowName;
            dimensionTags = current.execution.getDimensions().toTags();
        }

        String errorType = (t != null) ? t.getClass().getSimpleName() : "UnknownError";

        if (registry != null) {
            // Legacy interruption counter
            Counter.builder("flow_interruption_total")
                    .tag("flow", currentFlow)
                    .tag("failed_step", stepName)
                    .tag("error_type", errorType)
                    .tags(dimensionTags)
                    .description("Contador de interrupções de fluxo por etapa causadora e tipo de erro")
                    .register(registry)
                    .increment();

            // Candidate v2 canonical interruption counter
            Counter.builder("observability.flow.interruption")
                    .tag("flow", currentFlow)
                    .tag("failed_step", stepName)
                    .tag("error_type", errorType)
                    .tags(dimensionTags)
                    .description("Contador canônico de interrupções de fluxo por etapa causadora e tipo de erro")
                    .register(registry)
                    .increment();
        }
    }

    public static boolean hasInterruption() {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        return !stack.isEmpty() && stack.peek().failedStep != null;
    }

    public static String getFailedStep() {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        return (!stack.isEmpty()) ? stack.peek().failedStep : null;
    }

    public static void complete(MeterRegistry registry) {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        if (stack.isEmpty()) {
            return;
        }

        FlowContext context = stack.pop();
        if (stack.isEmpty()) {
            CURRENT_FLOW.remove();
        }

        long totalNanos = System.nanoTime() - context.startNanos;
        long sumStepsNanos = 0;
        String finalOutcome = (context.failedStep != null) ? "INTERRUPTED" : "SUCCESS";
        context.execution.complete(finalOutcome);

        Tags dimensionTags = context.execution.getDimensions().toTags();

        for (Map.Entry<String, Long> entry : context.stepDurations.entrySet()) {
            String stepName = entry.getKey();
            long stepDurationNanos = entry.getValue();
            String stepType = context.stepTypes.getOrDefault(stepName, "INTERNAL");
            sumStepsNanos += stepDurationNanos;

            if (registry != null) {
                // Legacy slice duration metric
                Timer.builder("flow_slice_duration_seconds")
                        .tag("flow", context.flowName)
                        .tag("step", stepName)
                        .tags(dimensionTags)
                        .description("Duração de cada fatia/subprocesso dentro do fluxo")
                        .register(registry)
                        .record(stepDurationNanos, TimeUnit.NANOSECONDS);
            }
        }

        if (registry != null) {
            // Legacy internal processing metric
            long internalNanos = Math.max(0, totalNanos - sumStepsNanos);
            Timer.builder("flow_slice_duration_seconds")
                    .tag("flow", context.flowName)
                    .tag("step", "Processamento Interno & Regras")
                    .tags(dimensionTags)
                    .description("Tempo de processamento interno e regras de negócio da aplicação")
                    .register(registry)
                    .record(internalNanos, TimeUnit.NANOSECONDS);

            // Legacy total flow metric
            Timer.builder("flow_total_duration_seconds")
                    .tag("flow", context.flowName)
                    .tags(dimensionTags)
                    .description("Duração total end-to-end do fluxo de entrada")
                    .register(registry)
                    .record(totalNanos, TimeUnit.NANOSECONDS);

            // Candidate v2 wall clock metric
            Timer.builder("observability.flow.duration")
                    .tag("flow", context.flowName)
                    .tag("status", finalOutcome)
                    .tags(dimensionTags)
                    .description("Duração wall-clock do fluxo end-to-end")
                    .register(registry)
                    .record(totalNanos, TimeUnit.NANOSECONDS);

            // Corporate Standard Metric Naming (Spec Section 23)
            Counter.builder(com.empresa.platform.observability.core.metric.MetricNamingPolicy.FLOW_EXECUTIONS)
                    .tag(com.empresa.platform.observability.core.metric.MetricNamingPolicy.TAG_FLOW, context.flowName)
                    .tag(com.empresa.platform.observability.core.metric.MetricNamingPolicy.TAG_STATUS, finalOutcome)
                    .tags(dimensionTags)
                    .description("Contador de execuções de fluxo corporativo")
                    .register(registry)
                    .increment();

            Timer.builder(com.empresa.platform.observability.core.metric.MetricNamingPolicy.FLOW_DURATION)
                    .tag(com.empresa.platform.observability.core.metric.MetricNamingPolicy.TAG_FLOW, context.flowName)
                    .tag(com.empresa.platform.observability.core.metric.MetricNamingPolicy.TAG_STATUS, finalOutcome)
                    .tags(dimensionTags)
                    .description("Duração de fluxo corporativo")
                    .register(registry)
                    .record(totalNanos, TimeUnit.NANOSECONDS);

            // Candidate v2 Latency Attribution Engine com dimensões
            LatencyAttributionEngine.recordAttributions(
                    context.flowName,
                    totalNanos,
                    context.stepDurations,
                    context.stepTypes,
                    context.execution.getDimensions().asMap(),
                    registry
            );
        }
    }

    public static void clear() {
        CURRENT_FLOW.remove();
    }
}
