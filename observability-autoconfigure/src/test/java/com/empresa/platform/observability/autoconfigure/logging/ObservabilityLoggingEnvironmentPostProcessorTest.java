package com.empresa.platform.observability.autoconfigure.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityLoggingEnvironmentPostProcessorTest {

    @Test
    @DisplayName("Deve carregar propriedades padrão do logback.yml no Environment com menor precedência")
    void shouldLoadLogbackYmlPropertiesIntoEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        ObservabilityLoggingEnvironmentPostProcessor processor = new ObservabilityLoggingEnvironmentPostProcessor();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().contains("observabilityLogbackDefaults")).isTrue();
        assertThat(environment.getProperty("logging.level.root")).isEqualTo("INFO");
        assertThat(environment.getProperty("logging.level.org.apache.kafka")).isEqualTo("WARN");
        assertThat(environment.getProperty("logging.pattern.console")).isNotNull();
    }
}
