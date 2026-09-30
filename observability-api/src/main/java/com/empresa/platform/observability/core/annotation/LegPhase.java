package com.empresa.platform.observability.core.annotation;

/**
 * Fases do ciclo de vida de uma perna de integração externa auditada por {@link LogLeg @LogLeg}.
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see LogLeg
 */
public enum LegPhase {

    /**
     * Fase de requisição (momento imediatamente anterior ao envio da chamada remota).
     */
    REQUEST,

    /**
     * Fase de resposta bem-sucedida (momento do retorno da resposta).
     */
    RESPONSE,

    /**
     * Ponto de início de processamento de uma perna assíncrona ou genérica.
     */
    START,

    /**
     * Ponto de encerramento bem-sucedido de processamento de uma perna assíncrona ou genérica.
     */
    END,

    /**
     * Fase de falha ou erro (quando a chamada remota lança exceção ou status de falha).
     */
    ERROR
}
