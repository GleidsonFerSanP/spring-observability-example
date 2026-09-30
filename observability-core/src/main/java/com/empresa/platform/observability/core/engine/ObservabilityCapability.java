package com.empresa.platform.observability.core.engine;

/**
 * Capacidades suportadas por uma Engine de Observabilidade.
 */
public enum ObservabilityCapability {
    /**
     * Suporte a descoberta e mapeamento dinâmico de topologia de serviços (Service Map).
     */
    SERVICE_MAP,

    /**
     * Suporte a projeção de topologia de fluxo por tags de span (Request Flow Map).
     */
    REQUEST_FLOW_MAP,

    /**
     * Suporte a monitoramento nativo de fluxos assíncronos e mensageria (Data Streams Monitoring).
     */
    DATA_STREAMS_MONITORING,

    /**
     * Suporte a decomposição nativa de latência descontando períodos de espera por filhos (Dependency Map).
     */
    NATIVE_LATENCY_ATTRIBUTION,

    /**
     * Exigência de polling periódico in-JVM para extração de lag de filas/tópicos (Kafka / SQS).
     */
    IN_JVM_LAG_POLLING,

    /**
     * Suporte a injeção e filtragem por tags customizadas em spans de tracing distribuído.
     */
    CUSTOM_SPAN_TAGS
}
