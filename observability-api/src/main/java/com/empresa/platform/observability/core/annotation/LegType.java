package com.empresa.platform.observability.core.annotation;

/**
 * Direção da perna de integração externa auditada por {@link LogLeg @LogLeg}.
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see LogLeg
 */
public enum LegType {

    /**
     * Perna de entrada (ex: requisições HTTP recebidas por Controllers, mensagens consumidas do Kafka).
     */
    INBOUND,

    /**
     * Perna de saída (ex: chamadas a APIs de terceiros via Feign/WebClient, envio de mensagens para Kafka/SQS).
     */
    OUTBOUND
}
