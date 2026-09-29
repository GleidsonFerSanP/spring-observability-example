package com.gleidsonfersanp.observability;

import com.empresa.platform.observability.core.flow.FlowContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ParallelFlowMathExperimentIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    public void setup() {
        meterRegistry.clear();
    }

    @Test
    public void testThreadLocalLosesContextInParallelExecution() throws Exception {
        FlowContext.start("test-parallel-flow");
        
        CompletableFuture<String> asyncResult = CompletableFuture.supplyAsync(() -> {
            // Because FlowContext uses ThreadLocal without context propagation decorators,
            // this will be "unknown" inside the async thread.
            return FlowContext.getCurrentFlowName();
        });
        
        String flowNameInAsync = asyncResult.get(5, TimeUnit.SECONDS);
        
        FlowContext.complete(meterRegistry);
        
        // This proves that ThreadLocal is insufficient for CompletableFuture
        assertThat(flowNameInAsync).isEqualTo("unknown");
    }
    
    @Test
    public void testMathErrorWithOverlappingSteps() throws Exception {
        // Demonstrate that total - Σsteps hides the real wall clock logic
        // when work duration > wall clock duration due to parallelism (or simulated parallelism)
        
        FlowContext.start("test-math-flow");
        
        // Simulating parallel execution where we magically managed to propagate context
        // Flow wall clock duration will be minimal (just the thread sleeping a bit)
        Thread.sleep(100); // 100ms wall clock
        
        // But we executed 3 parallel requests that took 300ms, 400ms, 500ms
        // If we record them:
        long sumOfSteps = TimeUnit.MILLISECONDS.toNanos(300 + 400 + 500); // 1200ms of work
        FlowContext.recordStep("Parallel-API-1", TimeUnit.MILLISECONDS.toNanos(300));
        FlowContext.recordStep("Parallel-API-2", TimeUnit.MILLISECONDS.toNanos(400));
        FlowContext.recordStep("Parallel-API-3", TimeUnit.MILLISECONDS.toNanos(500));
        
        FlowContext.complete(meterRegistry);
        
        // Now let's check the meters
        Collection<Timer> sliceTimers = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "test-math-flow")
                .timers();
                
        Timer internalProcessingTimer = sliceTimers.stream()
                .filter(t -> "Processamento Interno & Regras".equals(t.getId().getTag("step")))
                .findFirst()
                .orElseThrow();
                
        Timer totalTimer = meterRegistry.find("flow_total_duration_seconds")
                .tag("flow", "test-math-flow")
                .timer();
                
        // Total wall clock will be around 100ms (0.1s)
        double totalSeconds = totalTimer.totalTime(TimeUnit.SECONDS);
        assertThat(totalSeconds).isLessThan(0.3); // definitely less than 1200ms
        
        // The math in FlowContext calculates: totalNanos - sumStepsNanos
        // ~100ms - 1200ms = -1100ms
        // It applies Math.max(0, -1100ms) = 0
        assertThat(internalProcessingTimer.totalTime(TimeUnit.SECONDS)).isEqualTo(0.0);
        
        // This proves the observation in the spec:
        // "e o max(0, ...) esconderia o problema em vez de resolvê-lo."
        // Meaning latency composition pie chart is wrong, it adds up to 1200ms while wall clock is 100ms.
    }

    @Test
    public void testMathErrorWithNestedSteps() throws Exception {
        // Demonstrate that total - Σsteps fails when steps are nested (e.g. A calls B)
        // If we record A (which took 500ms) and B (which took 200ms and happened inside A),
        // we are double counting B.
        
        FlowContext.start("test-nested-flow");
        
        long start = System.nanoTime();
        
        // Step A takes 500ms total
        // Inside Step A, Step B takes 200ms
        // If we naively record both:
        FlowContext.recordStep("Step-A", TimeUnit.MILLISECONDS.toNanos(500));
        FlowContext.recordStep("Step-B", TimeUnit.MILLISECONDS.toNanos(200));
        
        // Let's pretend the whole flow took 600ms (100ms before A, 500ms for A)
        Thread.sleep(600);
        
        FlowContext.complete(meterRegistry);
        
        Collection<Timer> sliceTimers = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "test-nested-flow")
                .timers();
                
        Timer internalTimer = sliceTimers.stream()
                .filter(t -> "Processamento Interno & Regras".equals(t.getId().getTag("step")))
                .findFirst()
                .orElseThrow();
                
        // Total work recorded = 700ms. Flow wall clock = 600ms.
        // Internal processing will be 0ms because 600 - 700 < 0, max(0, -100) = 0.
        // But in reality, there was 100ms of internal processing outside A.
        // This proves nested spans break the current math.
        assertThat(internalTimer.totalTime(TimeUnit.SECONDS)).isEqualTo(0.0);
    }
    
    @Test
    public void testRetryHidesLatencyInCurrentModel() throws Exception {
        // Demonstrate that retries just sum up in the Map without attempt separation,
        // hiding the retry logic and wait times.
        
        FlowContext.start("test-retry-flow");
        
        // Simulating 3 attempts with wait times.
        // We must sleep so that the wall-clock time (totalNanos) actually exceeds sumStepsNanos.
        
        // Attempt 1: 100ms
        Thread.sleep(100);
        FlowContext.recordStep("Fraud-API", TimeUnit.MILLISECONDS.toNanos(100));
        
        // Backoff: 150ms
        Thread.sleep(150);
        
        // Attempt 2: 100ms
        Thread.sleep(100);
        FlowContext.recordStep("Fraud-API", TimeUnit.MILLISECONDS.toNanos(100));
        
        FlowContext.complete(meterRegistry);
        
        Collection<Timer> sliceTimers = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "test-retry-flow")
                .timers();
                
        Timer fraudApiTimer = sliceTimers.stream()
                .filter(t -> "Fraud-API".equals(t.getId().getTag("step")))
                .findFirst()
                .orElseThrow();
                
        Timer internalTimer = sliceTimers.stream()
                .filter(t -> "Processamento Interno & Regras".equals(t.getId().getTag("step")))
                .findFirst()
                .orElseThrow();
                
        // The Fraud-API timer only has ONE count, despite 2 attempts, because it merges by name
        assertThat(fraudApiTimer.count()).isEqualTo(1L);
        
        // The backoff time (150ms) ends up being attributed to "Processamento Interno & Regras"
        // which is misleading. It's not processing, it's retry wait.
        assertThat(internalTimer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(100.0);
    }
}
