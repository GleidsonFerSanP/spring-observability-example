package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertingProperties;
import com.empresa.platform.observability.core.annotation.ComponentType;
import com.empresa.platform.observability.core.annotation.FlowDimension;
import com.empresa.platform.observability.core.annotation.TrackFlow;
import com.empresa.platform.observability.core.annotation.TrackStep;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowTrackingAspectMdcTest {

    private FlowTrackingAspect aspect;

    static class SampleFlowService {
        private final AtomicReference<String> capturedFlow = new AtomicReference<>();
        private final AtomicReference<String> capturedStep = new AtomicReference<>();
        private final AtomicReference<String> capturedStepType = new AtomicReference<>();
        private final AtomicReference<String> capturedTenant = new AtomicReference<>();

        @TrackFlow(name = "checkout-flow")
        public void executeFlow(@FlowDimension(key = "tenant") String tenant, SampleFlowService self) {
            capturedFlow.set(MDC.get("flow"));
            capturedTenant.set(MDC.get("tenant"));
            self.executeStep();
        }

        @TrackStep(name = "validate-cart", type = ComponentType.BUSINESS)
        public void executeStep() {
            capturedStep.set(MDC.get("step"));
            capturedStepType.set(MDC.get("step.type"));
        }

        @TrackStep(name = "failing-step", type = ComponentType.DATABASE)
        public void failingStep() {
            assertThat(MDC.get("step")).isEqualTo("failing-step");
            throw new RuntimeException("DB Connection timeout");
        }
    }

    @BeforeEach
    void setUp() {
        MDC.clear();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AlertingProperties properties = new AlertingProperties();
        AlertDispatcher alertDispatcher = new AlertDispatcher(properties, java.util.List.of(), meterRegistry);
        aspect = new FlowTrackingAspect(
                meterRegistry,
                ObservationRegistry.create(),
                alertDispatcher,
                properties
        );
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private SampleFlowService createProxy(SampleFlowService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    @DisplayName("Deve injetar flow, step, step.type e FlowDimension no MDC e limpar após conclusão")
    void shouldInjectAndCleanMdcInFlowAndStep() {
        SampleFlowService service = new SampleFlowService();
        SampleFlowService proxy = createProxy(service);

        proxy.executeFlow("corporate-corp", proxy);

        // Verificações durante a execução
        assertThat(service.capturedFlow.get()).isEqualTo("checkout-flow");
        assertThat(service.capturedTenant.get()).isEqualTo("corporate-corp");
        assertThat(service.capturedStep.get()).isEqualTo("validate-cart");
        assertThat(service.capturedStepType.get()).isEqualTo("BUSINESS");

        // Após a conclusão, todas as chaves de MDC devem estar limpas
        assertThat(MDC.get("flow")).isNull();
        assertThat(MDC.get("step")).isNull();
        assertThat(MDC.get("step.type")).isNull();
        assertThat(MDC.get("tenant")).isNull();
    }

    @Test
    @DisplayName("Deve limpar step e step.type do MDC mesmo se o step falhar com exceção")
    void shouldCleanStepMdcOnFailure() {
        SampleFlowService service = new SampleFlowService();
        SampleFlowService proxy = createProxy(service);

        assertThatThrownBy(proxy::failingStep)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB Connection timeout");

        assertThat(MDC.get("step")).isNull();
        assertThat(MDC.get("step.type")).isNull();
    }
}
