package com.empresa.platform.observability.autoconfigure;

import feign.RequestInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FeignObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignObservabilityAutoConfiguration.class));

    @Test
    @DisplayName("Feign Interceptor registrado por padrão quando Feign está no classpath")
    void shouldRegisterFeignInterceptorByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RequestInterceptor.class);
        });
    }

    @Test
    @DisplayName("Feign Interceptor desabilitado quando observability.enabled=false")
    void shouldBackOffWhenObservabilityDisabled() {
        contextRunner.withPropertyValues("observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RequestInterceptor.class);
                });
    }

    @Test
    @DisplayName("Feign Interceptor desabilitado quando observability.feign.enabled=false")
    void shouldBackOffWhenFeignDisabled() {
        contextRunner.withPropertyValues("observability.feign.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RequestInterceptor.class);
                });
    }
}
