package com.empresa.platform.observability.core.flow;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyAttributionEngineTest {

    private MeterRegistry meterRegistry;
    private LatencyAttributionEngine engine;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        engine = new LatencyAttributionEngine();
    }

    @Test
    @DisplayName("Invariante 1: Execução Sequencial (sumWork <= wallClock) atribui trabalho integral e registra unattributed")
    void shouldAttributeSequentialWorkAndRecordUnattributed() {
        long wallClockNanos = TimeUnit.MILLISECONDS.toNanos(1000); // 1000ms
        FlowExecution flow = new FlowExecution("checkout-flow", wallClockNanos);

        flow.recordStep("validate-cart", TimeUnit.MILLISECONDS.toNanos(300));
        flow.recordStep("calculate-shipping", TimeUnit.MILLISECONDS.toNanos(400));

        LatencyAttributionEngine.recordAttributions(flow, meterRegistry);

        // 1. Trabalho real (work duration)
        double workValidate = meterRegistry.get("observability.flow.component.work.duration")
                .tag("flow", "checkout-flow")
                .tag("component", "validate-cart")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(workValidate).isEqualTo(300.0);

        double workShipping = meterRegistry.get("observability.flow.component.work.duration")
                .tag("flow", "checkout-flow")
                .tag("component", "calculate-shipping")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(workShipping).isEqualTo(400.0);

        // 2. Latência atribuída (attributed duration) deve ser idêntica ao trabalho no caso sequencial
        double attrValidate = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "checkout-flow")
                .tag("component", "validate-cart")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(attrValidate).isEqualTo(300.0);

        double attrShipping = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "checkout-flow")
                .tag("component", "calculate-shipping")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(attrShipping).isEqualTo(400.0);

        // 3. Tempo não atribuído (unattributed duration) = 1000 - 700 = 300ms
        double unattributed = meterRegistry.get("observability.flow.unattributed.duration")
                .tag("flow", "checkout-flow")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(unattributed).isEqualTo(300.0);

        // 4. Parallel overlap deve ser zero
        assertThat(meterRegistry.find("observability.flow.parallel.overlap.duration")
                .tag("flow", "checkout-flow")
                .timer()).isNull();
    }

    @Test
    @DisplayName("Invariante 2: Execução Paralela (sumWork > wallClock) pondera atribuição e registra parallel.overlap")
    void shouldAttributeParallelWorkAndRecordParallelOverlap() {
        long wallClockNanos = TimeUnit.MILLISECONDS.toNanos(1000); // 1000ms wall-clock
        FlowExecution flow = new FlowExecution("parallel-enrichment-flow", wallClockNanos);

        // Dois steps paralelos que duram 600ms e 800ms (soma = 1400ms > 1000ms)
        flow.recordStep("enrich-customer", TimeUnit.MILLISECONDS.toNanos(600));
        flow.recordStep("enrich-credit", TimeUnit.MILLISECONDS.toNanos(800));

        LatencyAttributionEngine.recordAttributions(flow, meterRegistry);

        // 1. Trabalho real individual preservado
        double workCustomer = meterRegistry.get("observability.flow.component.work.duration")
                .tag("flow", "parallel-enrichment-flow")
                .tag("component", "enrich-customer")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(workCustomer).isEqualTo(600.0);

        double workCredit = meterRegistry.get("observability.flow.component.work.duration")
                .tag("flow", "parallel-enrichment-flow")
                .tag("component", "enrich-credit")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(workCredit).isEqualTo(800.0);

        // 2. Latência atribuída proporcional:
        // enrich-customer: round(1000 * 600 / 1400) = 429ms
        // enrich-credit: round(1000 * 800 / 1400) = 571ms
        double attrCustomer = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "parallel-enrichment-flow")
                .tag("component", "enrich-customer")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(attrCustomer).isCloseTo(428.57, org.assertj.core.data.Offset.offset(1.0));

        double attrCredit = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "parallel-enrichment-flow")
                .tag("component", "enrich-credit")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(attrCredit).isCloseTo(571.43, org.assertj.core.data.Offset.offset(1.0));

        // Invariante canônico: Soma das latências atribuídas recompõe o wall clock (~1000ms)
        assertThat(attrCustomer + attrCredit).isCloseTo(1000.0, org.assertj.core.data.Offset.offset(1.0));

        // 3. Parallel overlap = 1400 - 1000 = 400ms
        double overlap = meterRegistry.get("observability.flow.parallel.overlap.duration")
                .tag("flow", "parallel-enrichment-flow")
                .timer()
                .totalTime(TimeUnit.MILLISECONDS);
        assertThat(overlap).isEqualTo(400.0);

        // 4. Unattributed deve ser zero
        assertThat(meterRegistry.find("observability.flow.unattributed.duration")
                .tag("flow", "parallel-enrichment-flow")
                .timer()).isNull();
    }

    @Test
    @DisplayName("Invariante 3: Propagação de FlowDimensions (variant/feature) para as métricas do motor de atribuição")
    void shouldAttributeSequentialWorkWithDimensions() {
        long wallClockNanos = TimeUnit.MILLISECONDS.toNanos(1000);
        FlowExecution flow = new FlowExecution("migrated-flow", wallClockNanos);
        flow.setFeature("payment-v2");
        flow.setVariant("new");
        flow.recordStep("cache-redis", TimeUnit.MILLISECONDS.toNanos(200));

        LatencyAttributionEngine.recordAttributions(flow, meterRegistry);

        Timer workTimer = meterRegistry.get("observability.flow.component.work.duration")
                .tag("flow", "migrated-flow")
                .tag("component", "cache-redis")
                .tag("variant", "new")
                .tag("feature", "payment-v2")
                .timer();
        assertThat(workTimer).isNotNull();
        assertThat(workTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200.0);

        Timer attrTimer = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "migrated-flow")
                .tag("component", "cache-redis")
                .tag("variant", "new")
                .tag("feature", "payment-v2")
                .timer();
        assertThat(attrTimer).isNotNull();
        assertThat(attrTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200.0);

        Timer unattributedTimer = meterRegistry.get("observability.flow.unattributed.duration")
                .tag("flow", "migrated-flow")
                .tag("variant", "new")
                .tag("feature", "payment-v2")
                .timer();
        assertThat(unattributedTimer).isNotNull();
        assertThat(unattributedTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(800.0);
    }
}
