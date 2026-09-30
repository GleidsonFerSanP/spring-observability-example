package com.empresa.platform.observability.autoconfigure.env;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Early environment post processor enforcing signal-level defaults before Spring Boot AutoConfigurations execute.
 * (Spec Sections 12, 13, 14, 15)
 */
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "observabilityPlatformDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String enabled = environment.getProperty("observability.enabled", "true");
        if ("false".equalsIgnoreCase(enabled)) {
            Map<String, Object> disabledDefaults = new HashMap<>();
            disabledDefaults.put("management.datadog.metrics.export.enabled", "false");
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, disabledDefaults));
            return;
        }

        String profile = environment.getProperty("observability.profile", "datadog");
        boolean allowDualExport = Boolean.parseBoolean(
                environment.getProperty("observability.metrics.allow-dual-export", "false")
        );

        Map<String, Object> defaults = new HashMap<>();

        if (allowDualExport) {
            // Dual metrics authorized (Spec 15)
            defaults.put("management.datadog.metrics.export.enabled", "true");
            defaults.put("management.prometheus.metrics.export.enabled", "true");
            if (!environment.containsProperty("management.datadog.metrics.export.api-key")) {
                defaults.put("management.datadog.metrics.export.api-key", "datadog-api-key-placeholder");
            }
        } else if ("prometheus".equalsIgnoreCase(profile)) {
            // Prometheus profile (Spec 14)
            defaults.put("management.datadog.metrics.export.enabled", "false");
            defaults.put("management.prometheus.metrics.export.enabled", "true");
        } else {
            // Default Datadog profile (Spec 13)
            defaults.put("management.datadog.metrics.export.enabled", "true");
            defaults.put("management.prometheus.metrics.export.enabled", "false");
            if (!environment.containsProperty("management.datadog.metrics.export.api-key")) {
                defaults.put("management.datadog.metrics.export.api-key", "datadog-api-key-placeholder");
            }
            // Disable Spring Boot Micrometer tracing so dd-java-agent is the sole tracing producer
            defaults.put("management.tracing.enabled", "false");
        }

        // Default endpoint exposure - avoid exposing '*' (Spec 61)
        if (!environment.containsProperty("management.endpoints.web.exposure.include")) {
            if ("prometheus".equalsIgnoreCase(profile) || allowDualExport) {
                defaults.put("management.endpoints.web.exposure.include", "health,info,prometheus,observability");
            } else {
                defaults.put("management.endpoints.web.exposure.include", "health,info,observability");
            }
        }

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
