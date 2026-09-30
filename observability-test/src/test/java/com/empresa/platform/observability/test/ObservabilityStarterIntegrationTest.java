package com.empresa.platform.observability.test;

import com.empresa.platform.observability.core.annotation.FlowDimension;
import com.empresa.platform.observability.core.annotation.TrackFlow;
import com.empresa.platform.observability.core.annotation.TrackStep;
import com.empresa.platform.observability.autoconfigure.ObservabilityAutoConfiguration;
import com.empresa.platform.observability.autoconfigure.actuator.ObservabilityEndpoint;
import com.empresa.platform.observability.autoconfigure.actuator.ObservabilityFlowsEndpoint;
import com.empresa.platform.observability.autoconfigure.actuator.ObservabilityMetricsEndpoint;
import com.empresa.platform.observability.autoconfigure.aspect.FlowTrackingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.LegLoggingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.SpelObservationAspect;
import com.empresa.platform.observability.autoconfigure.detector.TracingRuntimeDetector;
import com.empresa.platform.observability.autoconfigure.validator.ObservabilityTopologyValidator;
import com.empresa.platform.observability.core.engine.DatadogObservabilityEngine;
import com.empresa.platform.observability.core.engine.MicrometerObservabilityEngine;
import com.empresa.platform.observability.core.engine.ObservabilityEngine;
import com.empresa.platform.observability.core.flow.FlowVariant;
import com.empresa.platform.observability.core.flow.FlowVariantProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityStarterIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Configuration
    @EnableAspectJAutoProxy
    static class BaseTestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }

    @Nested
    @DisplayName("1. Profile Datadog (Default Corporate Profile)")
    class DatadogDefaultProfileTests {

        @Test
        @DisplayName("Deve instanciar DatadogObservabilityEngine e endpoints por padrão")
        void shouldInitializeDatadogAsDefaultProfile() {
            contextRunner.withUserConfiguration(BaseTestConfig.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(ObservabilityEngine.class);
                        assertThat(context.getBean(ObservabilityEngine.class))
                                .isInstanceOf(DatadogObservabilityEngine.class);

                        assertThat(context).hasSingleBean(ObservabilityEndpoint.class);
                        assertThat(context).hasSingleBean(ObservabilityMetricsEndpoint.class);
                        assertThat(context).hasSingleBean(ObservabilityFlowsEndpoint.class);

                        assertThat(context).hasSingleBean(FlowTrackingAspect.class);
                        assertThat(context).hasSingleBean(LegLoggingAspect.class);
                        assertThat(context).hasSingleBean(SpelObservationAspect.class);
                    });
        }
    }

    @Nested
    @DisplayName("2. Profile Prometheus")
    class PrometheusProfileTests {

        @Test
        @DisplayName("Deve instanciar MicrometerObservabilityEngine quando profile=prometheus")
        void shouldInitializeMicrometerEngineForPrometheusProfile() {
            contextRunner.withUserConfiguration(BaseTestConfig.class)
                    .withPropertyValues("observability.profile=prometheus")
                    .run(context -> {
                        assertThat(context).hasSingleBean(ObservabilityEngine.class);
                        assertThat(context.getBean(ObservabilityEngine.class))
                                .isInstanceOf(MicrometerObservabilityEngine.class);
                    });
        }
    }

    @Nested
    @DisplayName("3. Dual Metrics Authorization & Single Producer Policy")
    class DualMetricsPolicyTests {

        @Configuration
        static class PrometheusRegistryConfig {
            @Bean
            PrometheusMeterRegistry prometheusMeterRegistry() {
                return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            }
        }

        @Test
        @DisplayName("Deve falhar no startup se PrometheusMeterRegistry estiver ativo sob profile=datadog sem autorização explícita")
        void shouldFailStartupWhenPrometheusActiveWithoutDualExportAuthorization() {
            contextRunner.withUserConfiguration(BaseTestConfig.class, PrometheusRegistryConfig.class)
                    .withPropertyValues("observability.profile=datadog")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Conflict 4 (FATAL): Prometheus registry is ACTIVE under profile='datadog' without allow-dual-export=true!");
                    });
        }

        @Test
        @DisplayName("Deve permitir dual export se observability.metrics.allow-dual-export=true for configurado explicitamente")
        void shouldAllowDualExportWhenExplicitlyAuthorized() {
            contextRunner.withUserConfiguration(BaseTestConfig.class, PrometheusRegistryConfig.class)
                    .withPropertyValues(
                            "observability.profile=datadog",
                            "observability.metrics.allow-dual-export=true"
                    )
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        ObservabilityTopologyValidator validator = context.getBean(ObservabilityTopologyValidator.class);
                        assertThat(validator.isHealthy()).isTrue();
                        assertThat(validator.getViolations()).isEmpty();
                    });
        }
    }

    @Nested
    @DisplayName("4. Topology Conflicts & Agent Detection")
    class ConflictDetectionTests {

        @Test
        @DisplayName("Deve falhar fast se detector identificar conflito fatal entre Datadog Agent e OTel Agent")
        void shouldFailWhenConflictingAgentsDetected() {
            TracingRuntimeDetector simulatedDetector = new TracingRuntimeDetector() {
                @Override
                public boolean isDatadogAgentDetected() {
                    return true;
                }

                @Override
                public boolean isOtelAgentDetected() {
                    return true;
                }
            };

            contextRunner.withUserConfiguration(BaseTestConfig.class)
                    .withBean(TracingRuntimeDetector.class, () -> simulatedDetector)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasRootCauseInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("Conflict 1 (FATAL): Both Datadog Java Agent and OpenTelemetry Java Agent detected!");
                    });
        }
    }

    @Nested
    @DisplayName("5. Flow Tracking Lifecycle & Cardinality Guard Integration")
    class FlowLifecycleTests {

        @Service
        static class TestOrderService {

            @TrackFlow(name = "order-checkout")
            public String processOrder(
                    @FlowDimension(name = "tenant") String tenant,
                    @FlowDimension(name = "userId") String userId, // Should be rejected by cardinality policy
                    String orderId
            ) {
                return executeStep(orderId);
            }

            @TrackStep(name = "process-payment")
            public String executeStep(String orderId) {
                return "order-confirmed-" + orderId;
            }
        }

        @Configuration
        @EnableAspectJAutoProxy
        static class FlowTestConfig {
            @Bean
            MeterRegistry meterRegistry() {
                return new SimpleMeterRegistry();
            }

            @Bean
            ObservationRegistry observationRegistry() {
                return ObservationRegistry.create();
            }

            @Bean
            TestOrderService testOrderService() {
                return new TestOrderService();
            }

            @Bean
            FlowVariantProvider flowVariantProvider() {
                return () -> Optional.of(FlowVariant.of("checkout-v2"));
            }
        }

        @Test
        @DisplayName("Deve registrar métricas padronizadas corp.flow.* com variant e filtrar tags de alta cardinalidade")
        void shouldRecordStandardFlowMetricsWithVariantAndCardinalityGuard() {
            contextRunner.withUserConfiguration(FlowTestConfig.class)
                    .run(context -> {
                        TestOrderService service = context.getBean(TestOrderService.class);
                        MeterRegistry registry = context.getBean(MeterRegistry.class);

                        String result = service.processOrder("corporate-tenant", "user-secret-123", "order-999");
                        assertThat(result).isEqualTo("order-confirmed-order-999");

                        // Verify corp.flow.executions metric recorded
                        assertThat(registry.find("corp.flow.executions").counter()).isNotNull();
                        assertThat(registry.find("corp.flow.executions")
                                .tag("flow", "order-checkout")
                                .tag("variant", "checkout-v2")
                                .counter().count()).isEqualTo(1.0);

                        // Verify corp.flow.duration timer recorded
                        assertThat(registry.find("corp.flow.duration").timer()).isNotNull();
                        assertThat(registry.find("corp.flow.duration")
                                .tag("flow", "order-checkout")
                                .tag("variant", "checkout-v2")
                                .timer().count()).isEqualTo(1L);

                        // Verify Cardinality Policy blocked "userId"
                        assertThat(registry.find("corp.flow.executions")
                                .tagKeys("userId").counter()).isNull();
                    });
        }
    }
}
