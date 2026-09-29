package com.gleidsonfersanp.observability.observability.leg;

/**
 * Tipo de perna (Leg) no fluxo de comunicação.
 */
public enum LegType {
    /**
     * Entrada externa na aplicação (ex: Chamada REST de cliente ou mensagem de fila).
     */
    INBOUND,

    /**
     * Saída da aplicação para um terceiro (ex: Feign Client, publicação Kafka/SQS).
     */
    OUTBOUND,

    /**
     * Processamento interno específico entre camadas.
     */
    INTERNAL
}
