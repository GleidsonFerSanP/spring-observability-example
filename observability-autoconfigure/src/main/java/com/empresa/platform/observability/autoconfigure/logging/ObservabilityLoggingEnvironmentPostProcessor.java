package com.empresa.platform.observability.autoconfigure.logging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

/**
 * EnvironmentPostProcessor para carregar automaticamente a configuração centralizada
 * do {@code logback.yml} no Spring Boot Environment como propriedades padrão da plataforma.
 *
 * <p>As propriedades declaradas em {@code logback.yml} são carregadas com menor precedência
 * que o {@code application.yml} do próprio microsserviço, permitindo que a aplicação consumidora
 * herde todos os padrões da plataforma e sobreponha apenas o que desejar.</p>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 */
public class ObservabilityLoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Resource resource = new ClassPathResource("logback.yml");
        if (resource.exists()) {
            try {
                List<PropertySource<?>> propertySources = loader.load("observabilityLogbackDefaults", resource);
                for (PropertySource<?> propertySource : propertySources) {
                    environment.getPropertySources().addLast(propertySource);
                }
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
