package com.empresa.platform.observability.core.annotation;

/**
 * Estados canônicos de conclusão do ciclo de vida de um fluxo de observabilidade {@link TrackFlow @TrackFlow}.
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see TrackFlow
 * @see BusinessOutcome
 */
public enum FlowStatus {

    /**
     * Fluxo concluído com 100% de sucesso.
     */
    SUCCESS,

    /**
     * Fluxo finalizado com erro de negócio ou técnico.
     */
    ERROR,

    /**
     * Fluxo concluído com aplicação de fallback de contingência.
     */
    FALLBACK,

    /**
     * Fluxo interrompido abruptamente por exceção em um dos steps.
     */
    INTERRUPTED
}
