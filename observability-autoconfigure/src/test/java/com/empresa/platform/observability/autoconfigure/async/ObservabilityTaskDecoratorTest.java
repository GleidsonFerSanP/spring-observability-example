package com.empresa.platform.observability.autoconfigure.async;

import com.empresa.platform.observability.core.correlation.CorrelationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityTaskDecoratorTest {

    private ObservabilityTaskDecorator decorator;

    @BeforeEach
    void setUp() {
        decorator = new ObservabilityTaskDecorator();
        CorrelationContext.clear();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("TaskDecorator propaga CorrelationContext e MDC para thread trabalhadora assíncrona")
    void shouldPropagateContextAcrossThreads() throws ExecutionException, InterruptedException, TimeoutException {
        CorrelationContext.setCorrelationId("async-cid-999");
        MDC.put("tenant", "corporate-1");

        AtomicReference<String> capturedCid = new AtomicReference<>();
        AtomicReference<String> capturedMdc = new AtomicReference<>();

        Runnable task = () -> {
            capturedCid.set(CorrelationContext.getCorrelationId());
            capturedMdc.set(MDC.get("tenant"));
        };

        Runnable decoratedTask = decorator.decorate(task);

        CompletableFuture<Void> future = CompletableFuture.runAsync(decoratedTask);
        future.get(5, TimeUnit.SECONDS);

        assertThat(capturedCid.get()).isEqualTo("async-cid-999");
        assertThat(capturedMdc.get()).isEqualTo("corporate-1");
    }
}
