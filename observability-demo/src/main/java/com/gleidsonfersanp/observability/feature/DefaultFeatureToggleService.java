package com.gleidsonfersanp.observability.feature;

import com.empresa.platform.observability.core.feature.FeatureEvaluationListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação padrão de FeatureToggleService que se integra à SPI corporativa
 * FeatureEvaluationListener para enriquecer automaticamente Flow Dimensions, MDC e Tracing
 * sem contaminar o código de negócio com telemetria.
 */
@Service
public class DefaultFeatureToggleService implements FeatureToggleService {

    private final FeatureEvaluationListener listener;
    private final Map<String, Boolean> booleanFlags = new ConcurrentHashMap<>();
    private final Map<String, String> variantFlags = new ConcurrentHashMap<>();

    public DefaultFeatureToggleService(@Autowired(required = false) FeatureEvaluationListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean isEnabled(String featureName) {
        boolean enabled = booleanFlags.getOrDefault(featureName, false);
        String variant = enabled ? "new" : "legacy";
        if (listener != null) {
            listener.onFeatureEvaluated(featureName, variant, Map.of("enabled", String.valueOf(enabled)));
        }
        return enabled;
    }

    @Override
    public String evaluateVariant(String featureName, String defaultVariant) {
        String variant = variantFlags.getOrDefault(featureName, defaultVariant != null ? defaultVariant : "legacy");
        if (listener != null) {
            listener.onFeatureEvaluated(featureName, variant, Map.of("variant", variant));
        }
        return variant;
    }

    @Override
    public void setFeatureOverride(String featureName, boolean enabled) {
        booleanFlags.put(featureName, enabled);
    }

    @Override
    public void setVariantOverride(String featureName, String variant) {
        variantFlags.put(featureName, variant);
    }

    @Override
    public void clearOverrides() {
        booleanFlags.clear();
        variantFlags.clear();
    }
}
