package com.empresa.platform.observability.core.flow;

import com.empresa.platform.observability.core.feature.FlowFeatureEvaluationListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FlowContextTest {

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        FlowContext.clear();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        FlowContext.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("Deve registrar fluxo sequencial com decomposição de steps e processamento interno")
    void shouldRecordSequentialFlowAndSlices() {
        FlowContext.start("test-sequential-flow");
        FlowContext.recordStep("step-database", TimeUnit.MILLISECONDS.toNanos(150));
        FlowContext.complete(meterRegistry);

        Timer totalTimer = meterRegistry.find("flow_total_duration_seconds")
                .tag("flow", "test-sequential-flow")
                .timer();
        assertThat(totalTimer).isNotNull();

        Timer stepTimer = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "test-sequential-flow")
                .tag("step", "step-database")
                .timer();
        assertThat(stepTimer).isNotNull();
        assertThat(stepTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(150.0);
    }

    @Test
    @DisplayName("Deve registrar métricas canônicas e legadas de interrupção de fluxo")
    void shouldRecordInterruptionMetrics() {
        FlowContext.start("test-interrupted-flow");
        FlowContext.recordInterruption("step-payment", new RuntimeException("HTTP_500_TIMEOUT"), meterRegistry);

        // Métrica canônica (Candidate v2 Sec 28, 37)
        Counter canonicalCounter = meterRegistry.find("observability.flow.interruption")
                .tag("flow", "test-interrupted-flow")
                .tag("failed_step", "step-payment")
                .tag("error_type", "RuntimeException")
                .counter();
        assertThat(canonicalCounter).isNotNull();
        assertThat(canonicalCounter.count()).isEqualTo(1.0);

        // Métrica legada
        Counter legacyCounter = meterRegistry.find("flow_interruption_total")
                .tag("flow", "test-interrupted-flow")
                .tag("failed_step", "step-payment")
                .tag("error_type", "RuntimeException")
                .counter();
        assertThat(legacyCounter).isNotNull();
        assertThat(legacyCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Deve propagar FlowDimensions (variant e feature) para todas as métricas de flow e fatias")
    void shouldPropagateFlowDimensionsToAllMetrics() {
        FlowContext.start("payment-migration-flow");
        FlowContext.setFeature("payment-v2");
        FlowContext.setVariant("new");
        FlowContext.recordStep("step-redis", TimeUnit.MILLISECONDS.toNanos(80));
        FlowContext.complete(meterRegistry);

        Timer totalTimer = meterRegistry.find("flow_total_duration_seconds")
                .tag("flow", "payment-migration-flow")
                .tag("variant", "new")
                .timer();
        assertThat(totalTimer).isNotNull();

        Timer stepTimer = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "payment-migration-flow")
                .tag("step", "step-redis")
                .tag("variant", "new")
                .timer();
        assertThat(stepTimer).isNotNull();
        assertThat(stepTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(80.0);

        Timer workTimer = meterRegistry.find("observability.flow.component.work.duration")
                .tag("flow", "payment-migration-flow")
                .tag("component", "step-redis")
                .tag("variant", "new")
                .timer();
        assertThat(workTimer).isNotNull();
        assertThat(workTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(80.0);

        Timer wallClockTimer = meterRegistry.find("observability.flow.duration")
                .tag("flow", "payment-migration-flow")
                .tag("variant", "new")
                .timer();
        assertThat(wallClockTimer).isNotNull();

        Timer attributedTimer = meterRegistry.find("observability.flow.component.attributed.duration")
                .tag("flow", "payment-migration-flow")
                .tag("component", "step-redis")
                .tag("variant", "new")
                .timer();
        assertThat(attributedTimer).isNotNull();
        assertThat(attributedTimer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Deve enriquecer dimensões e MDC via FlowFeatureEvaluationListener de forma desacoplada")
    void shouldEnrichDimensionsAndMdcViaFeatureEvaluationListener() {
        FlowContext.start("user-registration-flow");
        FlowFeatureEvaluationListener listener = new FlowFeatureEvaluationListener();

        // Avaliação de feature flag booleana
        listener.onFeatureEvaluated("user-v2", true);

        assertThat(FlowContext.getCurrentDimensions().getVariant()).isEqualTo("new");
        assertThat(FlowContext.getCurrentDimensions().getFeature()).isEqualTo("user-v2");
        assertThat(MDC.get("variant")).isEqualTo("new");
        assertThat(MDC.get("feature.name")).isEqualTo("user-v2");
        assertThat(MDC.get("feature.variant")).isEqualTo("new");

        FlowContext.recordStep("step-sqs", TimeUnit.MILLISECONDS.toNanos(45));
        FlowContext.complete(meterRegistry);

        Timer workTimer = meterRegistry.find("observability.flow.component.work.duration")
                .tag("flow", "user-registration-flow")
                .tag("component", "step-sqs")
                .tag("variant", "new")
                .timer();
        assertThat(workTimer).isNotNull();
        assertThat(workTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(45.0);

        Timer attributedTimer = meterRegistry.find("observability.flow.component.attributed.duration")
                .tag("flow", "user-registration-flow")
                .tag("component", "step-sqs")
                .tag("variant", "new")
                .timer();
        assertThat(attributedTimer).isNotNull();
        assertThat(attributedTimer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThan(0.0);
    }
}
