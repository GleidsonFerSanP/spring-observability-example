package com.empresa.platform.observability.core.engine;

import com.empresa.platform.observability.core.flow.FlowContext;
import com.empresa.platform.observability.core.flow.FlowDimensions;
import com.empresa.platform.observability.core.flow.FlowExecution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityEngineTest {

    private ObservationRegistry observationRegistry;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        observationRegistry = ObservationRegistry.create();
        meterRegistry = new SimpleMeterRegistry();
        FlowContext.clear();
    }

    @AfterEach
    void tearDown() {
        FlowContext.clear();
    }

    @Test
    @DisplayName("Deve validar as capacidades nativas de Datadog, Micrometer e OpenTelemetry")
    void shouldValidateCapabilities() {
        EngineCapabilities ddCaps = EngineCapabilities.datadog();
        assertThat(ddCaps.getEngineName()).isEqualTo("datadog");
        assertThat(ddCaps.supportsServiceMap()).isTrue();
        assertThat(ddCaps.supportsRequestFlowMap()).isTrue();
        assertThat(ddCaps.supportsDataStreamsMonitoring()).isTrue();
        assertThat(ddCaps.supportsNativeLatencyAttribution()).isTrue();
        assertThat(ddCaps.requiresInJvmLagPolling()).isFalse();
        assertThat(ddCaps.hasCapability(ObservabilityCapability.SERVICE_MAP)).isTrue();
        assertThat(ddCaps.hasCapability(ObservabilityCapability.DATA_STREAMS_MONITORING)).isTrue();

        EngineCapabilities micrCaps = EngineCapabilities.micrometer();
        assertThat(micrCaps.getEngineName()).isEqualTo("micrometer");
        assertThat(micrCaps.supportsServiceMap()).isFalse();
        assertThat(micrCaps.supportsRequestFlowMap()).isFalse();
        assertThat(micrCaps.supportsDataStreamsMonitoring()).isFalse();
        assertThat(micrCaps.requiresInJvmLagPolling()).isTrue();
        assertThat(micrCaps.hasCapability(ObservabilityCapability.IN_JVM_LAG_POLLING)).isTrue();

        EngineCapabilities otelCaps = EngineCapabilities.openTelemetry();
        assertThat(otelCaps.getEngineName()).isEqualTo("opentelemetry");
        assertThat(otelCaps.supportsServiceMap()).isTrue();
        assertThat(otelCaps.supportsRequestFlowMap()).isTrue();
    }

    @Test
    @DisplayName("Deve executar ciclo completo com MicrometerObservabilityEngine")
    void shouldExecuteFlowWithMicrometerEngine() {
        ObservabilityEngine engine = new MicrometerObservabilityEngine(observationRegistry, meterRegistry);

        FlowDimensions dimensions = new FlowDimensions("legacy", "billing-feature", "exp-1");
        FlowContext.start("order-processing-flow");
        FlowScope flowScope = engine.startFlow("order-processing-flow", "ORCHESTRATION", dimensions);

        StepScope stepScope = engine.startStep("order-processing-flow", "database-save", "INTERNAL", dimensions);
        engine.completeStep("order-processing-flow", "database-save", "INTERNAL", TimeUnit.MILLISECONDS.toNanos(100), dimensions, stepScope);

        FlowExecution execution = FlowContext.getCurrentExecution();
        engine.completeFlow(execution, flowScope);

        Timer totalTimer = meterRegistry.find("flow_total_duration_seconds")
                .tag("flow", "order-processing-flow")
                .tag("variant", "legacy")
                .timer();
        assertThat(totalTimer).isNotNull();

        Timer sliceTimer = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "order-processing-flow")
                .tag("step", "database-save")
                .tag("variant", "legacy")
                .timer();
        assertThat(sliceTimer).isNotNull();
    }

    @Test
    @DisplayName("Deve executar ciclo completo com DatadogObservabilityEngine")
    void shouldExecuteFlowWithDatadogEngine() {
        ObservabilityEngine engine = new DatadogObservabilityEngine(observationRegistry, meterRegistry);
        assertThat(engine.getCapabilities().supportsRequestFlowMap()).isTrue();

        FlowDimensions dimensions = new FlowDimensions("new", "cache-migration", "exp-2");
        FlowContext.start("user-registration-flow");
        FlowScope flowScope = engine.startFlow("user-registration-flow", "ORCHESTRATION", dimensions);

        StepScope stepScope = engine.startStep("user-registration-flow", "redis-cache", "CACHE", dimensions);
        engine.completeStep("user-registration-flow", "redis-cache", "CACHE", TimeUnit.MILLISECONDS.toNanos(30), dimensions, stepScope);

        FlowExecution execution = FlowContext.getCurrentExecution();
        engine.completeFlow(execution, flowScope);

        Timer totalTimer = meterRegistry.find("flow_total_duration_seconds")
                .tag("flow", "user-registration-flow")
                .tag("variant", "new")
                .timer();
        assertThat(totalTimer).isNotNull();

        Timer sliceTimer = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "user-registration-flow")
                .tag("step", "redis-cache")
                .tag("variant", "new")
                .timer();
        assertThat(sliceTimer).isNotNull();
    }

    @Test
    @DisplayName("Deve registrar interrupção e falha com DatadogObservabilityEngine")
    void shouldRecordInterruptionWithDatadogEngine() {
        ObservabilityEngine engine = new DatadogObservabilityEngine(observationRegistry, meterRegistry);

        FlowDimensions dimensions = new FlowDimensions("v2", "payment-route", "exp-3");
        FlowContext.start("payment-flow");
        FlowScope flowScope = engine.startFlow("payment-flow", "BUSINESS", dimensions);

        StepScope stepScope = engine.startStep("payment-flow", "acquirer-api", "INTEGRATION_HTTP", dimensions);
        RuntimeException ex = new RuntimeException("Acquirer timeout");
        engine.recordStepInterruption("payment-flow", "acquirer-api", ex, dimensions, stepScope);
        engine.recordFlowInterruption("payment-flow", "acquirer-api", ex, dimensions, flowScope);

        FlowExecution execution = FlowContext.getCurrentExecution();
        engine.completeFlow(execution, flowScope);

        assertThat(meterRegistry.find("flow_interruption_total")
                .tag("flow", "payment-flow")
                .tag("failed_step", "acquirer-api")
                .tag("variant", "v2")
                .counter()).isNotNull();
    }

    @Test
    @DisplayName("Deve garantir segurança contra exceções na chamada de OtelSpanBridge")
    void shouldSafelyCallOtelSpanBridge() {
        assertThat(OtelSpanBridge.isAvailable()).isTrue(); // opentelemetry-api presente no core
        OtelSpanBridge.setAttribute("flow.name", "test-flow");
        OtelSpanBridge.setAttribute(null, null);
    }
}
