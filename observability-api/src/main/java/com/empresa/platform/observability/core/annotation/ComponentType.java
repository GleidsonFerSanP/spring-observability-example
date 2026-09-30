package com.empresa.platform.observability.core.annotation;

/**
 * Tipos canônicos de componentes e subprocessos para classificação de latência,
 * rastreamento distribuído e geração de topologia de serviços (Candidate Architecture v2 - Seção 50).
 */
public enum ComponentType {

    // --- Integrações HTTP & RPC ---
    /** Chamadas REST e clientes HTTP genéricos (RestClient, WebClient, HttpClient). */
    HTTP,
    /** Clientes declarativos Spring Cloud OpenFeign. */
    FEIGN,
    /** Chamadas RPC de alto desempenho via gRPC / Protocol Buffers. */
    GRPC,

    // --- Persistência & Cache ---
    /** Operações de persistência, consultas SQL, JPA/Hibernate, JDBC ou NoSQL. */
    DATABASE,
    /** Operações de cache local ou distribuído (Redis, Caffeine, Memcached, Hazelcast). */
    CACHE,

    // --- Mensageria & Filas ---
    /** Mensageria genérica Apache Kafka. */
    KAFKA,
    /** Publicação de mensagens/eventos em tópicos do Apache Kafka (KafkaProducer). */
    KAFKA_PRODUCER,
    /** Processamento/consumo de records em tópicos do Apache Kafka (KafkaListener). */
    KAFKA_CONSUMER,
    /** Mensageria genérica AWS SQS. */
    SQS,
    /** Envio de mensagens para filas do AWS Simple Queue Service (SqsTemplate). */
    SQS_PRODUCER,
    /** Consumo de mensagens de filas do AWS Simple Queue Service (SqsListener). */
    SQS_CONSUMER,
    /** Publicação e distribuição de eventos em tópicos AWS SNS. */
    SNS,
    /** Mensageria empresarial síncrona/assíncrona via JMS (ActiveMQ, IBM MQ, Artemis). */
    JMS,

    // --- Execução Local & Negócio ---
    /** Regras de negócio, cálculos de domínio e orquestrações locais (Padrão do @TrackStep). */
    BUSINESS,
    /** Processamento técnico pesado, transformações de dados in-memory, parsing e serialização. */
    INTERNAL,
    /** Execução em thread-pools secundários, tarefas assíncronas e workers (CompletableFuture, TaskExecutor). */
    EXECUTOR,

    // --- Padrões de Resiliência ---
    /** Tentativas de reexecução e retries com backoff. */
    RETRY,
    /** Chamadas monitoradas ou protegidas por Circuit Breakers. */
    CIRCUIT_BREAKER,
    /** Isolamento de concorrência e limitação de threads concorrentes via Bulkhead. */
    BULKHEAD,
    /** Controle de vazão e limitação de taxa de requisições por segundo. */
    RATE_LIMITER,

    // --- Extensibilidade ---
    /** Subprocessos específicos que não se enquadram nas categorias padronizadas acima. */
    CUSTOM;

    /**
     * Retorna se o componente representa uma dependência ou integração externa (I/O de rede/disco).
     */
    public boolean isIntegration() {
        return this != BUSINESS && this != INTERNAL && this != EXECUTOR;
    }

    /**
     * Retorna se o componente pertence ao ecossistema de mensageria assíncrona orientada a eventos.
     */
    public boolean isMessaging() {
        return this == KAFKA || this == KAFKA_PRODUCER || this == KAFKA_CONSUMER
                || this == SQS || this == SQS_PRODUCER || this == SQS_CONSUMER
                || this == SNS || this == JMS;
    }

    /**
     * Retorna se o componente atua como uma barreira ou padrão de resiliência.
     */
    public boolean isResilience() {
        return this == RETRY || this == CIRCUIT_BREAKER || this == BULKHEAD || this == RATE_LIMITER;
    }

    /**
     * Retorna se o componente representa execução local in-memory na JVM sem I/O de rede externo.
     */
    public boolean isLocal() {
        return this == BUSINESS || this == INTERNAL || this == EXECUTOR;
    }

    /**
     * Retorna a categoria macro do componente para agregações em dashboards analíticos de alto nível.
     */
    public String getCategory() {
        if (this == HTTP || this == FEIGN || this == GRPC) return "HTTP";
        if (this == DATABASE) return "DATABASE";
        if (this == CACHE) return "CACHE";
        if (isMessaging()) return "MESSAGING";
        if (isResilience()) return "RESILIENCE";
        if (this == BUSINESS) return "BUSINESS";
        if (this == INTERNAL || this == EXECUTOR) return "INTERNAL";
        return "CUSTOM";
    }
}
