package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.autoconfigure.aspect.FlowTrackingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.LegLoggingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.SpelObservationAspect;
import com.empresa.platform.observability.autoconfigure.async.ObservabilityTaskDecorator;
import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertingProperties;
import com.empresa.platform.observability.core.leg.SpelMaskingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    static class TestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }

    @Test
    @DisplayName("Cenário Padrão: Starter ativo por padrão registra todos os componentes")
    void shouldRegisterObservabilityBeansByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FlowTrackingAspect.class);
            assertThat(context).hasSingleBean(LegLoggingAspect.class);
            assertThat(context).hasSingleBean(SpelObservationAspect.class);
            assertThat(context).hasSingleBean(ObservabilityTaskDecorator.class);
            assertThat(context).hasSingleBean(CorrelationIdFilter.class);
            assertThat(context).hasSingleBean(AlertDispatcher.class);
            assertThat(context).hasSingleBean(SpelMaskingService.class);
        });
    }

    @Test
    @DisplayName("Master Toggle: observability.enabled=false desliga 100% dos componentes do starter")
    void shouldBackOffWhenObservabilityDisabled() {
        contextRunner.withPropertyValues("observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FlowTrackingAspect.class);
                    assertThat(context).doesNotHaveBean(LegLoggingAspect.class);
                    assertThat(context).doesNotHaveBean(SpelObservationAspect.class);
                    assertThat(context).doesNotHaveBean(ObservabilityTaskDecorator.class);
                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
                    assertThat(context).doesNotHaveBean(AlertDispatcher.class);
                });
    }

    @Test
    @DisplayName("Granular Toggle: observability.flow-tracking.enabled=false desliga apenas FlowTrackingAspect")
    void shouldBackOffFlowTrackingWhenPropertyFalse() {
        contextRunner.withPropertyValues("observability.flow-tracking.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(FlowTrackingAspect.class);
                    assertThat(context).hasSingleBean(LegLoggingAspect.class);
                    assertThat(context).hasSingleBean(SpelObservationAspect.class);
                });
    }

    @Test
    @DisplayName("Granular Toggle: observability.leg-logging.enabled=false desliga apenas LegLoggingAspect")
    void shouldBackOffLegLoggingWhenPropertyFalse() {
        contextRunner.withPropertyValues("observability.leg-logging.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LegLoggingAspect.class);
                    assertThat(context).hasSingleBean(FlowTrackingAspect.class);
                });
    }

    @Test
    @DisplayName("Granular Toggle: observability.spel-observation.enabled=false desliga apenas SpelObservationAspect")
    void shouldBackOffSpelObservationWhenPropertyFalse() {
        contextRunner.withPropertyValues("observability.spel-observation.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SpelObservationAspect.class);
                    assertThat(context).hasSingleBean(FlowTrackingAspect.class);
                });
    }

    @Test
    @DisplayName("Granular Toggle: observability.correlation.enabled=false desliga CorrelationIdFilter")
    void shouldBackOffCorrelationWhenPropertyFalse() {
        contextRunner.withPropertyValues("observability.correlation.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CorrelationIdFilter.class);
                    assertThat(context).hasSingleBean(FlowTrackingAspect.class);
                });
    }

    @Test
    @DisplayName("Granular Toggle: observability.alerting.enabled=false desliga AlertDispatcher")
    void shouldBackOffAlertingWhenPropertyFalse() {
        contextRunner.withPropertyValues("observability.alerting.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AlertDispatcher.class);
                    assertThat(context).hasSingleBean(FlowTrackingAspect.class);
                });
    }

    @Test
    @DisplayName("Granular Toggle: observability.async-decorator.enabled=false desliga ObservabilityTaskDecorator")
    void shouldBackOffAsyncDecoratorWhenPropertyFalse() {
        contextRunner.withPropertyValues("observability.async-decorator.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ObservabilityTaskDecorator.class);
                    assertThat(context).hasSingleBean(FlowTrackingAspect.class);
                });
    }

    @Configuration
    static class CustomAlertDispatcherConfig {
        @Bean
        AlertDispatcher customAlertDispatcher() {
            return new AlertDispatcher(new AlertingProperties(), null, null);
        }
    }

    @Test
    @DisplayName("Spring Boot Pattern: Custom @Bean prevalece sobre @ConditionalOnMissingBean")
    void shouldRespectCustomUserBeanOverride() {
        contextRunner.withUserConfiguration(CustomAlertDispatcherConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AlertDispatcher.class);
                    assertThat(context.getBean(AlertDispatcher.class))
                            .isSameAs(context.getBean("customAlertDispatcher"));
                });
    }

    @Test
    @DisplayName("Engine Selection: Padrão é DatadogObservabilityEngine (Spec Section 4)")
    void shouldRegisterDatadogEngineByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(com.empresa.platform.observability.core.engine.ObservabilityEngine.class);
            assertThat(context.getBean(com.empresa.platform.observability.core.engine.ObservabilityEngine.class))
                    .isInstanceOf(com.empresa.platform.observability.core.engine.DatadogObservabilityEngine.class);
        });
    }

    @Test
    @DisplayName("Engine Selection: observability.profile=prometheus ativa MicrometerObservabilityEngine")
    void shouldRegisterMicrometerEngineWhenPrometheusConfigured() {
        contextRunner.withPropertyValues("observability.profile=prometheus")
                .run(context -> {
                    assertThat(context).hasSingleBean(com.empresa.platform.observability.core.engine.ObservabilityEngine.class);
                    assertThat(context.getBean(com.empresa.platform.observability.core.engine.ObservabilityEngine.class))
                            .isInstanceOf(com.empresa.platform.observability.core.engine.MicrometerObservabilityEngine.class);
                });
    }
}
