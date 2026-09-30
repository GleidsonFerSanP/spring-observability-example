package com.empresa.platform.observability.core.flow;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Motor de Atribuição de Latência (Candidate Architecture v2 - Seções 4, 9, 10, 11, 12, 37, 38).
 * Garante as três dimensões temporais fundamentais do Flow com suporte a dimensões analíticas:
 * 1. Wall-Clock Duration (tempo percebido externamente: observability.flow.duration)
 * 2. Work Duration (soma de todo trabalho executado: observability.flow.component.work.duration)
 * 3. Attributed Duration (latência matematicamente atribuída: observability.flow.component.attributed.duration)
 * Além das métricas analíticas:
 * - observability.flow.parallel.overlap.duration
 * - observability.flow.unattributed.duration
 */
public class LatencyAttributionEngine {

    public static void recordAttributions(FlowExecution flowExecution, MeterRegistry registry) {
        if (flowExecution == null || registry == null) {
            return;
        }
        Map<String, Long> stepDurations = new java.util.LinkedHashMap<>();
        Map<String, String> stepTypes = new java.util.LinkedHashMap<>();
        for (StepExecution step : flowExecution.getStepExecutions()) {
            stepDurations.merge(step.getStepName(), step.getDurationNanos(), Long::sum);
            stepTypes.putIfAbsent(step.getStepName(), step.getType());
        }
        recordAttributions(
                flowExecution.getFlowName(),
                flowExecution.getWallClockDurationNanos(),
                stepDurations,
                stepTypes,
                flowExecution.getDimensions().asMap(),
                registry
        );
    }

    public static void recordAttributions(
            String flowName,
            long wallClockNanos,
            Map<String, Long> stepDurations,
            Map<String, String> stepTypes,
            MeterRegistry registry
    ) {
        recordAttributions(flowName, wallClockNanos, stepDurations, stepTypes, Collections.emptyMap(), registry);
    }

    public static void recordAttributions(
            String flowName,
            long wallClockNanos,
            Map<String, Long> stepDurations,
            Map<String, String> stepTypes,
            Map<String, String> dimensions,
            MeterRegistry registry
    ) {
        if (registry == null) {
            return;
        }

        Tags dimensionTags = toTags(dimensions);

        long sumWorkNanos = 0;
        for (Map.Entry<String, Long> entry : stepDurations.entrySet()) {
            String component = entry.getKey();
            long workDuration = entry.getValue();
            String type = stepTypes.getOrDefault(component, "INTERNAL");
            sumWorkNanos += workDuration;

            recordWorkMetric(registry, flowName, component, type, dimensionTags, workDuration);
        }

        if (sumWorkNanos <= wallClockNanos) {
            // Cenário sequencial: a atribuição direta é idêntica à duração do trabalho
            for (Map.Entry<String, Long> entry : stepDurations.entrySet()) {
                String component = entry.getKey();
                long workDuration = entry.getValue();
                String type = stepTypes.getOrDefault(component, "INTERNAL");

                recordAttributedMetric(registry, flowName, component, type, dimensionTags, workDuration);
            }

            long unattributedNanos = Math.max(0, wallClockNanos - sumWorkNanos);
            recordAttributedMetric(registry, flowName, "Internal & Framework", "INTERNAL", dimensionTags, unattributedNanos);

            Timer.builder("observability.flow.unattributed.duration")
                    .tag("flow", flowName)
                    .tags(dimensionTags)
                    .description("Duração de latência ainda não explicada por subprocessos instrumentados no fluxo")
                    .register(registry)
                    .record(unattributedNanos, TimeUnit.NANOSECONDS);

        } else {
            // Cenário com paralelismo ou sobreposição temporal (Work > Wall-Clock):
            // Aplica normalização proporcional de contribuição para manter a invariante do gráfico de composição:
            // Σ attributed component duration ≈ flow wall-clock duration
            for (Map.Entry<String, Long> entry : stepDurations.entrySet()) {
                String component = entry.getKey();
                long workDuration = entry.getValue();
                String type = stepTypes.getOrDefault(component, "INTERNAL");

                double proportion = (double) workDuration / sumWorkNanos;
                long attributedNanos = Math.round(wallClockNanos * proportion);

                recordAttributedMetric(registry, flowName, component, type, dimensionTags, attributedNanos);
            }

            long overlapNanos = sumWorkNanos - wallClockNanos;
            Timer.builder("observability.flow.parallel.overlap.duration")
                    .tag("flow", flowName)
                    .tags(dimensionTags)
                    .description("Duração de sobreposição de subprocessos paralelos no fluxo")
                    .register(registry)
                    .record(overlapNanos, TimeUnit.NANOSECONDS);
        }
    }

    private static Tags toTags(Map<String, String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return Tags.empty();
        }
        Tags tags = Tags.empty();
        for (Map.Entry<String, String> entry : dimensions.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                tags = tags.and(Tag.of(entry.getKey(), entry.getValue()));
            }
        }
        return tags;
    }

    private static void recordWorkMetric(
            MeterRegistry registry,
            String flowName,
            String component,
            String type,
            Tags dimensionTags,
            long durationNanos
    ) {
        Timer.builder("observability.flow.component.work.duration")
                .tag("flow", flowName)
                .tag("component", component)
                .tag("type", type)
                .tags(dimensionTags)
                .description("Duração real de trabalho do componente independente de paralelismo")
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private static void recordAttributedMetric(
            MeterRegistry registry,
            String flowName,
            String component,
            String type,
            Tags dimensionTags,
            long durationNanos
    ) {
        Timer.builder("observability.flow.component.attributed.duration")
                .tag("flow", flowName)
                .tag("component", component)
                .tag("type", type)
                .tags(dimensionTags)
                .description("Duração de latência atribuída ao componente no gráfico de composição do fluxo")
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
