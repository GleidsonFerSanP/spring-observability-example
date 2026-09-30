package com.empresa.platform.observability.core.feature;

import java.util.Map;

/**
 * SPI para interceptação de avaliações de Feature Flags / Feature Toggles.
 * Desacopla regras de negócio da instrumentação de observabilidade do Flow.
 */
public interface FeatureEvaluationListener {

    /**
     * Notificado quando uma feature flag / toggle é avaliada.
     *
     * @param featureName nome canônico da feature flag
     * @param variant variante resolvida (ex: "legacy", "new", "v2", "control", "experiment")
     * @param metadata metadados adicionais de baixa cardinalidade (opcional)
     */
    void onFeatureEvaluated(String featureName, String variant, Map<String, String> metadata);

    /**
     * Conveniência para toggles booleanos simples.
     *
     * @param featureName nome canônico da feature flag
     * @param enabled se o toggle está habilitado (true -> "new", false -> "legacy")
     */
    default void onFeatureEvaluated(String featureName, boolean enabled) {
        onFeatureEvaluated(featureName, enabled ? "new" : "legacy", Map.of("enabled", String.valueOf(enabled)));
    }
}
