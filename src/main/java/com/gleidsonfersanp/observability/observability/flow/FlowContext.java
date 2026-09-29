package com.gleidsonfersanp.observability.observability.flow;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FlowContext {

    private static final ThreadLocal<Deque<FlowContext>> CURRENT_FLOW = ThreadLocal.withInitial(ArrayDeque::new);

    private final String flowName;
    private final long startNanos;
    private final Map<String, Long> stepDurations = new LinkedHashMap<>();

    private FlowContext(String flowName) {
        this.flowName = flowName;
        this.startNanos = System.nanoTime();
    }

    public static void start(String flowName) {
        CURRENT_FLOW.get().push(new FlowContext(flowName));
    }

    public static void recordStep(String stepName, long durationNanos) {
        Deque<FlowContext> stack = CURRENT_FLOW.get();
        if (!stack.isEmpty()) {
            FlowContext current = stack.peek();
            current.stepDurations.merge(stepName, durationNanos, Long::sum);
        }
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

        for (Map.Entry<String, Long> entry : context.stepDurations.entrySet()) {
            sumStepsNanos += entry.getValue();
            Timer.builder("flow_slice_duration_seconds")
                    .tag("flow", context.flowName)
                    .tag("step", entry.getKey())
                    .description("Duração de cada fatia/subprocesso dentro do fluxo")
                    .register(registry)
                    .record(entry.getValue(), TimeUnit.NANOSECONDS);
        }

        long internalNanos = Math.max(0, totalNanos - sumStepsNanos);
        Timer.builder("flow_slice_duration_seconds")
                .tag("flow", context.flowName)
                .tag("step", "Processamento Interno & Regras")
                .description("Tempo de processamento interno e regras de negócio da aplicação")
                .register(registry)
                .record(internalNanos, TimeUnit.NANOSECONDS);

        Timer.builder("flow_total_duration_seconds")
                .tag("flow", context.flowName)
                .description("Duração total end-to-end do fluxo de entrada")
                .register(registry)
                .record(totalNanos, TimeUnit.NANOSECONDS);
    }
}
