package com.gleidsonfersanp.observability.observability.leg;

/**
 * Fase do ciclo de vida da perna (Leg).
 */
public enum LegPhase {
    /**
     * Início da perna (envio da requisição ou recepção da entrada).
     */
    REQUEST,

    /**
     * Término da perna (recepção da resposta ou envio da saída final).
     */
    RESPONSE
}
