package com.empresa.platform.observability.autoconfigure.async;

import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Decorator padronizado para propagação de contexto (MDC, Correlation ID, Tracing e Observações)
 * ao atravessar threads em ThreadPoolTaskExecutor e @Async (Candidate Architecture v2 - Seção 8).
 */
public class ObservabilityTaskDecorator implements TaskDecorator {

    private final ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        Runnable contextWrapped = snapshotFactory.captureAll().wrap(runnable);

        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            } else {
                MDC.clear();
            }
            try {
                contextWrapped.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
