package com.empresa.platform.observability.core.annotation;

/**
 * Constantes canônicas de desfecho de negócio (Business Outcome) para etiquetagem
 * de status final em execuções de fluxos {@link TrackFlow @TrackFlow}.
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see TrackFlow
 * @see FlowStatus
 */
public final class BusinessOutcome {

    /**
     * O fluxo de negócio foi concluído com sucesso e aprovado.
     */
    public static final String COMPLETED = "COMPLETED";

    /**
     * O fluxo de negócio foi rejeitado por regras de negócio (ex: crédito negado, antifraude).
     */
    public static final String REJECTED = "REJECTED";

    /**
     * O fluxo de negócio falhou devido a erros técnicos ou operacionais.
     */
    public static final String FAILED = "FAILED";

    /**
     * O fluxo foi concluído de forma degradada através da aplicação de fallback resiliente.
     */
    public static final String FALLBACK_APPLIED = "FALLBACK_APPLIED";

    private BusinessOutcome() {
    }
}
