package com.empresa.platform.observability.core.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FlowExecutionConcurrencyTest {

    @Test
    @DisplayName("Deve registrar centenas de StepExecutions concorrentes de forma thread-safe sem corromper o modelo")
    void shouldSafelyRecordStepsConcurrently() throws InterruptedException {
        int threads = 20;
        int stepsPerThread = 50;
        FlowExecution flow = new FlowExecution("multi-threaded-flow");

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < stepsPerThread; j++) {
                        flow.recordStep("step-" + threadIdx + "-" + j, 1_000_000L); // 1ms cada
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        int expectedTotalSteps = threads * stepsPerThread;
        assertThat(flow.getStepExecutions()).hasSize(expectedTotalSteps);

        long expectedTotalWorkNanos = expectedTotalSteps * 1_000_000L;
        assertThat(flow.getWorkDurationNanos()).isEqualTo(expectedTotalWorkNanos);
    }
}
