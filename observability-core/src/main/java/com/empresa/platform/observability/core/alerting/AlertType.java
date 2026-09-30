package com.empresa.platform.observability.core.alerting;

public enum AlertType {
    CIRCUIT_BREAKER_OPEN("Disjuntor Aberto - Falha Crítica na Integração"),
    CIRCUIT_BREAKER_DEGRADED("Disjuntor em Meio-Aberto - Testando Recuperação"),
    INTEGRATION_LATENCY_SLA_BREACH("Violação de SLA de Latência em Integração Externa"),
    FLOW_LATENCY_SLA_BREACH("Violação de SLA de Latência End-to-End no Fluxo"),
    KAFKA_LAG_HIGH("Acúmulo Crítico de Lag no Consumidor Kafka"),
    SQS_BACKLOG_HIGH("Fila SQS com Volume Elevado de Mensagens Pendentes"),
    DATABASE_POOL_STARVATION("Esgotamento Crítico do Pool de Conexões de Banco de Dados"),
    FLOW_STEP_INTERRUPTION("Interrupção no Fluxo - Falha Crítica em Subprocesso");

    private final String description;

    AlertType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
