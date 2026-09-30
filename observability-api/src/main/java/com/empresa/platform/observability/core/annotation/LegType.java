package com.empresa.platform.observability.core.annotation;

/**
 * Classificação arquitetural da natureza e direção de uma <b>Perna de Comunicação (Communication Leg)</b>
 * auditada por {@link LogLeg @LogLeg}.
 *
 * <p>O tipo da perna define o contexto semântico da integração, influenciando diretamente:
 * <ul>
 *   <li>A chave {@code leg_type} injetada no SLF4J MDC durante o ciclo de vida do método;</li>
 *   <li>O campo estruturado {@code "type"} emitido nos logs forenses de {@code AUDIT_LEG_LOGGER};</li>
 *   <li>A agregação, filtros e criação de alertas/dashboards em agregadores de log (Grafana Loki, Datadog, ELK).</li>
 * </ul>
 * </p>
 *
 * <h3>Taxonomia Arquitetural de Pernas:</h3>
 * <table border="1">
 *   <tr><th>Tipo</th><th>Sentido / Natureza</th><th>Exemplos Canônicos</th></tr>
 *   <tr><td>{@link #INBOUND}</td><td>Entrada remota</td><td>Controllers REST, endpoints gRPC, WebSockets</td></tr>
 *   <tr><td>{@link #OUTBOUND}</td><td>Saída remota síncrona</td><td>Clientes OpenFeign, WebClient, RestTemplate</td></tr>
 *   <tr><td>{@link #CONFIG}</td><td>Resolução de configurações</td><td>Spring Cloud Config, Vault, Consul, Feature Flags, Unleash</td></tr>
 *   <tr><td>{@link #DATABASE}</td><td>Acesso a banco de dados</td><td>Spring Data JPA, JDBC, Hibernate, MongoDB, DynamoDB</td></tr>
 *   <tr><td>{@link #MESSAGING}</td><td>Mensageria assíncrona</td><td>Apache Kafka, RabbitMQ, AWS SQS/SNS</td></tr>
 *   <tr><td>{@link #CACHE}</td><td>Acesso a cache</td><td>Redis, Memcached, Hazelcast, Caffeine</td></tr>
 *   <tr><td>{@link #INTERNAL}</td><td>Processamento interno</td><td>Cálculo de score, pipelines pesados em memória, batch jobs</td></tr>
 * </table>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see LogLeg
 * @see LegPhase
 */
public enum LegType {

    /**
     * Perna de entrada remota na aplicação.
     *
     * <p>Utilizada para auditar requisições de clientes externos recebidas pelos pontos de contato
     * da aplicação, tais como Controllers REST ({@code @RestController}), servidores gRPC,
     * endpoints GraphQL ou conexões WebSocket.</p>
     *
     * <b>Exemplo:</b>
     * <pre>{@code
     * @PostMapping("/orders")
     * @LogLeg(target = "order-ingress", type = LegType.INBOUND, includePayload = true)
     * public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) { ... }
     * }</pre>
     */
    INBOUND,

    /**
     * Perna de saída remota síncrona da aplicação para serviços externos.
     *
     * <p>É o valor <b>padrão</b> de {@link LogLeg#type()}, sendo utilizado para chamadas HTTP
     * síncronas através de clientes OpenFeign ({@code @FeignClient}), WebClient, RestTemplate
     * ou clientes gRPC para outros microsserviços ou parceiros externos.</p>
     *
     * <b>Exemplo:</b>
     * <pre>{@code
     * @FeignClient(name = "customer-service")
     * public interface CustomerClient {
     *     @GetMapping("/customers/{id}")
     *     @LogLeg(target = "customer-service", type = LegType.OUTBOUND)
     *     CustomerDto getCustomer(@PathVariable("id") String id);
     * }
     * }</pre>
     */
    OUTBOUND,

    /**
     * Perna de carregamento ou resolução de configurações internas, remotas, feature flags ou segredos.
     *
     * <p>Utilizada quando a aplicação realiza a busca, decodificação ou validação de parâmetros de
     * configuração dinâmicos ou remotos (ex: Spring Cloud Config Server, HashiCorp Vault, AWS Secrets Manager,
     * AWS AppConfig, Consul KV) ou serviços de Feature Toggles / Feature Flags (ex: Unleash, LaunchDarkly,
     * Togglz, split.io ou provedores customizados de flag).</p>
     *
     * <p>Permite registrar com precisão forense no log estruturado qual configuração ou flag foi consultada,
     * a chave utilizada, o valor retornado (devidamente mascarado se sensível via {@link LogLeg#mask()})
     * e o tempo gasto para resolver a propriedade.</p>
     *
     * <b>Exemplo com Provedor de Feature Flags:</b>
     * <pre>{@code
     * @Service
     * public class FeatureFlagService {
     *
     *     @LogLeg(target = "feature-flag-provider", type = LegType.CONFIG, includePayload = true)
     *     public boolean isFeatureEnabled(String flagName, String userId) {
     *         return flagProvider.checkToggle(flagName, userId);
     *     }
     * }
     * }</pre>
     *
     * <b>Exemplo com Resolução de Segredos / Credenciais no Vault:</b>
     * <pre>{@code
     * @Component
     * public class VaultSecretsLoader {
     *
     *     @LogLeg(
     *         target = "hashicorp-vault",
     *         type = LegType.CONFIG,
     *         includePayload = true,
     *         mask = { @MaskField(expression = "token", pattern = MaskPattern.PASSWORD) }
     *     )
     *     public VaultCredentials fetchSecret(String path) {
     *         return vaultTemplate.read(path, VaultCredentials.class).getData();
     *     }
     * }
     * }</pre>
     */
    CONFIG,

    /**
     * Perna de persistência, consulta ou transação com bancos de dados.
     *
     * <p>Utilizada para auditar operações com bancos de dados relacionais (PostgreSQL, Oracle, MySQL, SQL Server)
     * via Spring Data JPA, JDBC, Hibernate ou MyBatis, bem como bancos NoSQL e documentais
     * (MongoDB, DynamoDB, Cassandra, Couchbase).</p>
     *
     * <p>Permite isolar a telemetria, latência de I/O de disco e eventuais erros de concorrência ou timeout
     * de banco em relação às chamadas de rede HTTP comuns.</p>
     *
     * <b>Exemplo:</b>
     * <pre>{@code
     * @Repository
     * public class OrderCustomRepository {
     *
     *     @LogLeg(target = "postgres-orders", type = LegType.DATABASE)
     *     public OrderEntity findLockedOrder(UUID orderId) {
     *         return entityManager.find(OrderEntity.class, orderId, LockModeType.PESSIMISTIC_WRITE);
     *     }
     * }
     * }</pre>
     */
    DATABASE,

    /**
     * Perna de comunicação assíncrona com brokers de mensageria ou sistemas de streaming de eventos.
     *
     * <p>Utilizada tanto na publicação/envio (producer/publish) quanto no consumo/processamento
     * (consumer/listener) de mensagens e tópicos em tecnologias como Apache Kafka, RabbitMQ,
     * AWS SQS/SNS, Google Cloud Pub/Sub, Azure Service Bus ou ActiveMQ.</p>
     *
     * <p>Permite diferenciar o comportamento e SLAs de integrações assíncronas baseadas em eventos
     * de chamadas remotas HTTP síncronas bloqueantes.</p>
     *
     * <b>Exemplo de Publicação no Kafka:</b>
     * <pre>{@code
     * @Component
     * public class OrderEventProducer {
     *
     *     @LogLeg(target = "kafka-orders-topic", type = LegType.MESSAGING)
     *     public void publishOrderCreated(OrderCreatedEvent event) {
     *         kafkaTemplate.send("orders.v1", event.getOrderId(), event);
     *     }
     * }
     * }</pre>
     */
    MESSAGING,

    /**
     * Perna de acesso a camadas de cache em memória ou distribuído.
     *
     * <p>Utilizada para auditar operações de leitura, escrita ou invalidação em caches como
     * Redis, Memcached, Hazelcast, Infinispan ou caches locais de alta performance (Caffeine, Guava).</p>
     *
     * <p>Permite rastrear latência de lookups, chaves consultadas e correlacionar cache misses
     * com eventuais degradações de performance no banco de dados.</p>
     *
     * <b>Exemplo:</b>
     * <pre>{@code
     * @Component
     * public class SessionCacheService {
     *
     *     @LogLeg(target = "redis-session-cache", type = LegType.CACHE)
     *     public UserSession getSession(String sessionId) {
     *         return redisTemplate.opsForValue().get("session:" + sessionId);
     *     }
     * }
     * }</pre>
     */
    CACHE,

    /**
     * Perna de computação, processamento ou transformação interna crítica.
     *
     * <p>Utilizada para blocos de processamento em memória pesados ou regras de negócio críticas
     * (ex: motores de cálculo de risco/score de crédito, validação de regras de antifraude locais,
     * rotinas criptográficas pesadas, geração de relatórios ou batch jobs locais) que não realizam
     * I/O externo direto, mas que devem ter sua latência, status e árvore de execução preservados
     * na hierarquia sequencial de pernas ({@code leg_number} e {@code leg_parent}).</p>
     *
     * <b>Exemplo:</b>
     * <pre>{@code
     * @Service
     * public class RiskCalculatorService {
     *
     *     @LogLeg(target = "credit-score-engine", type = LegType.INTERNAL)
     *     public RiskScore calculateRisk(CustomerProfile profile, OrderData order) {
     *         return riskMatrixEngine.evaluate(profile, order);
     *     }
     * }
     * }</pre>
     */
    INTERNAL
}
