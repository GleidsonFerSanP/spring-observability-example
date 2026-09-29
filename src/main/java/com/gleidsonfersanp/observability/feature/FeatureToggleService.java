package com.gleidsonfersanp.observability.feature;

public interface FeatureToggleService {

    /**
     * Verifica se uma feature flag está ativa.
     *
     * @param featureName identificador da feature flag
     * @return true se ativa, false caso contrário
     */
    boolean isEnabled(String featureName);

    /**
     * Avalia uma variante multi-estágio da feature flag (ex: legacy, canary, v2, v3).
     *
     * @param featureName identificador da feature flag
     * @param defaultVariant variante padrão caso não configurada
     * @return nome da variante ativa
     */
    String evaluateVariant(String featureName, String defaultVariant);

    /**
     * Sobrescreve dinamicamente o status booleano da feature flag para testes/canary.
     */
    void setFeatureOverride(String featureName, boolean enabled);

    /**
     * Sobrescreve dinamicamente a variante da feature flag para testes/canary.
     */
    void setVariantOverride(String featureName, String variant);

    /**
     * Limpa todas as sobrescritas dinâmicas.
     */
    void clearOverrides();
}
