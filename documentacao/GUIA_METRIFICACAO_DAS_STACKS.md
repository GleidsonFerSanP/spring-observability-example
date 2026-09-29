# Guia Prático: Como Metrificar Cada Stack Tecnológica

Este guia é o manual técnico definitivo de **como instrumentar e metrificar cada uma das tecnologias (stacks)** utilizadas na arquitetura deste projeto. O objetivo é fornecer uma receita de engenharia clara, reprodutível e não-intrusiva para qualquer time ou microsserviço corporativo.

---

## 📑 Índice das Stacks

1. [Spring Boot MVC / REST Controllers (Entrypoints Síncronos)](#1-spring-boot-mvc--rest-controllers-entrypoints-s%C3%ADncronos)
2. [OpenFeign (Integrações HTTP Externas)](#2-openfeign-integra%C3%A7%C3%B5es-http-externas)
3. [Resilience4j (Disjuntores e Tolerância a Falhas)](#3-resilience4j-disjuntores-e-toler%C3%A2ncia-a-falhas)
4. [Apache Kafka (Streaming e Mensageria Orientada a Eventos)](#4-apache-kafka-streaming-e-mensageria-orientada-a-eventos)
5. [AWS SQS / LocalStack (Filas em Nuvem)](#5-aws-sqs--localstack-filas-em-nuvem)
6. [Banco de Dados Relacional (JPA / Hibernate / HikariCP)](#6-banco-de-dados-relacional-jpa--hibernate--hikaricp)
7. [Distributed Tracing & OpenTelemetry (W3C / Jaeger)](#7-distributed-tracing--opentelemetry-w3c--jaeger)
8. [Alarmística In-App & SLA Guards Não-Intrusivos](#8-alarm%C3%ADstica-in-app--sla-guards-n%C3%A3o-intrusivos)

---

## 1. Spring Boot MVC / REST Controllers (Entrypoints Síncronos)

### 🎯 O que Metrificar (Golden Signals)
- **Tráfego**: Requisições por segundo (RPS) por rota e método HTTP.
- **Erros**: Taxa de respostas `4xx` (erros de cliente) e `5xx` (falhas de servidor).
- **Latência**: Tempo total ponta a ponta (E2E) percebido pelo cliente externo.
- **Saturação**: Threads do servidor web Tomcat/Undertow ocupadas.

### 📦 Dependências Maven (`pom.xml`)
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

### ⚙️ Configuração (`application.yml`)
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
      sla:
        http.server.requests: 500ms, 1000ms, 2000ms
```

### 💻 Implementação Não-Intrusiva
1. **Instrumentação Nativa do Spring**: Toda rota mapeada com `@GetMapping`, `@PostMapping`, etc., é automaticamente interceptada pelo Spring MVC gerando a métrica `http.server.requests`.
2. **Decomposição E2E Customizada (`@TrackFlow`)**:
   ```java
   @RestController
   @RequestMapping("/api/v1/orchestrator")
   public class UserOrchestratorController {

       @GetMapping("/users/{userId}")
       @TrackFlow("GET /api/v1/orchestrator/users/{userId}")
       public ResponseEntity<UserProfile> getUserProfile(@PathVariable String userId) {
           return ResponseEntity.ok(userOrchestratorService.fetchAndProvisionUserProfile(userId));
       }
   }
   ```
3. **Tratamento Centralizado de Erros (`@RestControllerAdvice`)**:
   Captura exceções de negócio e integrações em [`GlobalExceptionHandler`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/api/GlobalExceptionHandler.java), garantindo status HTTP semânticos (400, 404, 429, 502, 503) para que as métricas reflitam a realidade.

### 📊 Métricas Chave
| Métrica | Tipo | Tags Principais | Significado |
| :--- | :---: | :--- | :--- |
| `http_server_requests_seconds_count` | Counter | `uri`, `method`, `status`, `exception` | Volume de chamadas recebidas |
| `http_server_requests_seconds_sum` | Counter | `uri`, `method`, `status` | Tempo total acumulado de resposta |
| `flow_total_duration_seconds_*` | Timer | `flow`, `status` | Duração do fluxo de negócio customizado |

### 📈 Queries PromQL Recomendadas
- **Throughput (RPS por endpoint)**:
  ```promql
  sum(rate(http_server_requests_seconds_count[1m])) by (uri, method)
  ```
- **Taxa de Erro 5xx (%)**:
  ```promql
  sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m])) 
  / 
  sum(rate(http_server_requests_seconds_count[1m])) * 100
  ```
- **Latência Média E2E (s)**:
  ```promql
  sum(rate(flow_total_duration_seconds_sum{flow="GET /api/v1/orchestrator/users/{userId}"}[1m]))
  /
  sum(rate(flow_total_duration_seconds_count{flow="GET /api/v1/orchestrator/users/{userId}"}[1m]))
  ```

---

## 2. OpenFeign (Integrações HTTP Externas)

### 🎯 O que Metrificar
- **Latência de Cada Step/Subprocesso**: Quanto tempo cada API terceira (Customer, Billing, Notification) consumiu.
- **Taxa de Falhas por Cliente**: Identificar parceiro externo instável antes que o disjuntor abra.
- **Enriquecimento com Metadados**: Extrair campos dos DTOs de retorno (ex: tipo de plano `billingType`) sem poluir services.

### 📦 Dependências Maven (`pom.xml`)
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-micrometer</artifactId>
</dependency>
```

### ⚙️ Configuração (`application.yml`)
```yaml
feign:
  micrometer:
    enabled: true
  client:
    config:
      default:
        connectTimeout: 2000
        readTimeout: 3000
```

### 💻 Implementação Não-Intrusiva
Anotar as interfaces declarativas com `@TrackStep` e `@ObservationTag`:
```java
@FeignClient(name = "billing-service", url = "${app.integrations.billing.url}")
public interface BillingClient {

    @TrackStep("API Billing (GET /billing/{userId})")
    @ObservationTag(key = "client", expression = "'billing'")
    @ObservationTag(key = "billing_type", expression = "#result?.billingType()?.name()")
    @CircuitBreaker(name = "billing-service")
    @GetMapping("/billing/accounts/{userId}")
    BillingDto getBillingInfo(@PathVariable("userId") String userId);
}
```
- O aspect [`FlowTrackingAspect`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/flow/FlowTrackingAspect.java) mede a duração do step e armazena na fatia da pizza do `FlowContext`.
- O aspect [`SpelObservationAspect`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/SpelObservationAspect.java) extrai `#result` dinamicamente com SpEL e anexa as tags de baixa cardinalidade.

### 📊 Métricas Chave
| Métrica | Tipo | Tags | Significado |
| :--- | :---: | :--- | :--- |
| `flow_slice_duration_seconds_sum` | Counter | `flow`, `step`, `status` | Tempo gasto no subprocesso dentro do fluxo |
| `http_client_requests_seconds_count` | Counter | `client`, `method`, `status` | Total de invocações HTTP Feign |

### 📈 Queries PromQL
- **Fatias de Latência da Pizza (Taxa por Step)**:
  ```promql
  sum(rate(flow_slice_duration_seconds_sum{flow="GET /api/v1/orchestrator/users/{userId}"}[1m])) by (step)
  ```

---

## 3. Resilience4j (Disjuntores e Tolerância a Falhas)

### 🎯 O que Metrificar
- **Estado do Disjuntor**: Fechado (0 - saudável), Meio-Aberto (1 - teste), Aberto (2 - falha crítica).
- **Taxa de Chamadas Bem-Sucedidas vs. Falhas / Fallbacks**.
- **Percentual de Erros (Failure Rate)** avaliado pelo sliding window.

### 📦 Dependências Maven (`pom.xml`)
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-micrometer</artifactId>
</dependency>
```

### ⚙️ Configuração (`application.yml`)
```yaml
resilience4j:
  circuitbreaker:
    instances:
      customer-service:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        registerHealthIndicator: true
      billing-service:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
      notification-service:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
```

### 💻 Implementação Não-Intrusiva
1. **Disjuntor Declarativo**: `@CircuitBreaker(name = "billing-service")` nas interfaces Feign.
2. **Listener de Transição de Estado Reativo**:
   Implementar `RegistryEventConsumer<CircuitBreaker>` em [`CircuitBreakerAlertListener`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/alerting/CircuitBreakerAlertListener.java):
   ```java
   @Component
   public class CircuitBreakerAlertListener implements RegistryEventConsumer<CircuitBreaker> {
       @Override
       public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> entryAddedEvent) {
           entryAddedEvent.getAddedEntry().getEventPublisher().onStateTransition(event -> {
               // Dispara alerta instantâneo quando o disjuntor abre (OPEN) ou entra em teste (HALF_OPEN)
               alertDispatcher.dispatch(...);
           });
       }
   }
   ```

### 📊 Métricas Chave
| Métrica | Tipo | Tags | Significado |
| :--- | :---: | :--- | :--- |
| `resilience4j_circuitbreaker_state` | Gauge | `name`, `state="closed\|open\|half_open"` | Estado atual da máquina de estados (0 ou 1) |
| `resilience4j_circuitbreaker_calls_seconds_count` | Counter | `name`, `kind="successful\|failed"` | Volume de chamadas e resultado |
| `resilience4j_circuitbreaker_failure_rate` | Gauge | `name` | Percentual de erro calculado pelo disjuntor |

### 📈 Queries PromQL
- **Card Stat de Disjuntor Fechado/Aberto**:
  ```promql
  resilience4j_circuitbreaker_state{name="customer-service", state="closed"}
  ```
- **Taxa de Erros das Chamadas sob o Disjuntor (%)**:
  ```promql
  sum(rate(resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}[5m])) by (name)
  /
  (sum(rate(resilience4j_circuitbreaker_calls_seconds_count{kind="successful"}[5m])) by (name) +
   sum(rate(resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}[5m])) by (name)) * 100
  ```

---

## 4. Apache Kafka (Streaming e Mensageria Orientada a Eventos)

### 🎯 O que Metrificar
- **Consumer Lag Autoritativo**: Total de mensagens acumuladas nas partições que o consumidor ainda não processou.
- **Taxa de Produção**: Mensagens postadas por segundo (`msg/s`).
- **Taxa de Consumo e Tempo de Processamento de Lote/Mensagem**.

### 📦 Dependências Maven (`pom.xml`)
```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

### ⚙️ Configuração (`application.yml`)
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: user-orchestrator-group
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      observation-enabled: true # Habilita Micrometer no Kafka
    template:
      observation-enabled: true
```

### 💻 Implementação Não-Intrusiva
1. **Medição Autoritativa de Lag via `AdminClient` (`KafkaLagMetricsBinder`)**:
   Em vez de confiar em métricas in-memory que travam quando a JVM congela, consultar o broker diretamente:
   ```java
   @Component
   public class KafkaLagMetricsBinder implements MeterBinder {
       @Scheduled(fixedDelay = 5000)
       public void updateLag() {
           var consumerOffsets = adminClient.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
           var endOffsets = adminClient.listOffsets(latestOffsetsQuery).all().get();
           long lag = endOffsets.get(tp).offset() - consumerOffsets.get(tp).offset();
           // Registra Gauge no Micrometer
       }
   }
   ```
2. **Entrada do Consumidor como Fluxo (`@TrackFlow`)**:
   ```java
   @KafkaListener(topics = "user-registration-topic", groupId = "user-orchestrator-group")
   @TrackFlow("Kafka Consumer: user-registration-topic")
   public void consumeUserRegistration(UserRegistrationRequest event) {
       userOrchestratorService.processUserRegistration(event);
   }
   ```

### 📊 Métricas Chave
| Métrica | Tipo | Tags | Significado |
| :--- | :---: | :--- | :--- |
| `kafka_consumer_lag_records` | Gauge | `topic`, `group` | Mensagens pendentes no broker |
| `messaging_produce_seconds_count` | Counter | `method`, `topic` | Taxa de envio de mensagens |
| `messaging_consume_seconds_count` | Counter | `method`, `topic` | Taxa de processamento de mensagens |

### 📈 Queries PromQL
- **Lag Real por Tópico**:
  ```promql
  sum(kafka_consumer_lag_records) by (topic)
  ```
- **Throughput de Produção Kafka**:
  ```promql
  sum(rate(messaging_produce_seconds_count{method=~".*Kafka.*"}[1m])) by (method)
  ```

---

## 5. AWS SQS / LocalStack (Filas em Nuvem)

### 🎯 O que Metrificar
- **Profundidade das Filas (Queue Depth)**: Total de mensagens aguardando processamento (`ApproximateNumberOfMessages`).
- **Mensagens Em Voo (In-Flight)**: Mensagens entregues a instâncias ativas aguardando delete ou timeout de visibilidade.
- **Descoberta Automática de Filas**: Monitorar novas filas sem intervenção manual.

### 📦 Dependências Maven (`pom.xml`)
```xml
<dependency>
    <groupId>io.awspring.cloud</groupId>
    <artifactId>spring-cloud-aws-starter-sqs</artifactId>
    <version>3.1.1</version>
</dependency>
```

### ⚙️ Configuração (`application.yml`)
```yaml
spring:
  cloud:
    aws:
      sqs:
        endpoint: http://localhost:4566
      region:
        static: us-east-1
      credentials:
        access-key: test
        secret-key: test
```

### 💻 Implementação Não-Intrusiva
1. **Descoberta Dinâmica de Filas (`SqsMetricsBinder`)**:
   Implementa `MeterBinder`, varre a conta via `sqsAsyncClient.listQueues()` a cada 10 segundos e registra Gauges automáticos:
   ```java
   @Component
   public class SqsMetricsBinder implements MeterBinder {
       @Scheduled(fixedDelay = 10000)
       public void scanQueues() {
           ListQueuesResponse queues = sqsAsyncClient.listQueues().get();
           for (String queueUrl : queues.queueUrls()) {
               var attrs = sqsAsyncClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                   .queueUrl(queueUrl)
                   .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                   .build()).get();
               long depth = Long.parseLong(attrs.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
               // Registra Gauge dinâmico: sqs_queue_depth{queue="nome-da-fila"}
           }
       }
   }
   ```
2. **Producers Rastreados (`@TrackStep`)**:
   ```java
   @Component
   public class SqsUserProducer {
       @TrackStep("Publicação SQS (user-audit-queue)")
       @Observed(name = "messaging.produce")
       public void sendAuditLog(AuditMessage message) {
           sqsTemplate.send("user-audit-queue", message);
       }
   }
   ```

### 📊 Métricas Chave
| Métrica | Tipo | Tags | Significado |
| :--- | :---: | :--- | :--- |
| `sqs_queue_depth` | Gauge | `queue` | Mensagens prontas na fila SQS |
| `sqs_queue_in_flight` | Gauge | `queue` | Mensagens em processamento (invisíveis) |

### 📈 Queries PromQL
- **Profundidade de Todas as Filas SQS**:
  ```promql
  sqs_queue_depth
  ```

---

## 6. Banco de Dados Relacional (JPA / Hibernate / HikariCP)

### 🎯 O que Metrificar
- **Utilização do Pool**: Conexões ativas vs ociosas vs tamanho máximo.
- **Fila de Espera (Pending Connections)**: Threads bloqueadas aguardando conexão.
- **Timeouts de Conexão**: Ocorrências de `SQLTransientConnectionException`.
- **Estatísticas do Hibernate**: Tempo de execução de queries e flush.

### 📦 Dependências Maven (`pom.xml`)
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

### ⚙️ Configuração (`application.yml`)
```yaml
spring:
  datasource:
    hikari:
      pool-name: observability-hikari-pool
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 3000
      idle-timeout: 600000
      max-lifetime: 1800000
  jpa:
    properties:
      hibernate:
        generate_statistics: true
```

### 💻 Implementação Não-Intrusiva
1. **Pool Nomeado**: O Actuator detecta o HikariCP e exporta nativamente o conjunto de métricas sob o rótulo `pool="observability-hikari-pool"`.
2. **Watchdog de Inanição (`HikariPoolAlertWatcher`)**:
   Monitora se há threads acumuladas na fila de espera antes que ocorra erro 500:
   ```java
   @Component
   public class HikariPoolAlertWatcher {
       @Scheduled(fixedDelay = 10000)
       public void monitorPool() {
           Search search = meterRegistry.find("hikaricp.connections.pending").tag("pool", poolName);
           if (search != null && search.gauge() != null && search.gauge().value() > 0) {
               alertDispatcher.dispatch(new AlertEvent(
                   AlertType.DATABASE_POOL_STARVATION,
                   AlertSeverity.WARNING,
                   "HikariPool",
                   poolName,
                   "Threads aguardando liberação de conexões do pool."
               ));
           }
       }
   }
   ```

### 📊 Métricas Chave
| Métrica | Tipo | Tags | Significado |
| :--- | :---: | :--- | :--- |
| `hikaricp_connections_active` | Gauge | `pool` | Conexões executando queries no momento |
| `hikaricp_connections_idle` | Gauge | `pool` | Conexões livres no pool |
| `hikaricp_connections_pending` | Gauge | `pool` | Threads bloqueadas aguardando conexão |
| `hikaricp_connections_timeout_total` | Counter | `pool` | Falhas de estouro de `connection-timeout` |

### 📈 Queries PromQL
- **Saturação do Pool (%)**:
  ```promql
  (hikaricp_connections_active{pool="observability-hikari-pool"} 
   / 
   hikaricp_connections_max{pool="observability-hikari-pool"}) * 100
  ```
- **Fila de Espera por Conexão**:
  ```promql
  hikaricp_connections_pending{pool="observability-hikari-pool"}
  ```

---

## 7. Distributed Tracing & OpenTelemetry (W3C / Jaeger)

### 🎯 O que Metrificar
- **Caminho Completo do Trace**: Correlação entre chamada REST $\rightarrow$ Feign $\rightarrow$ Kafka $\rightarrow$ SQS $\rightarrow$ Banco de Dados.
- **Identificadores Únicos**: `traceId` e `spanId` injetados em logs e cabeçalhos HTTP (`traceparent`).
- **Span Tags**: Tags de negócio de baixa e média cardinalidade.

### 📦 Dependências Maven (`pom.xml`)
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### ⚙️ Configuração (`application.yml`)
```yaml
management:
  tracing:
    sampling:
      probability: 1.0 # 100% de amostragem em desenvolvimento/laboratório
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

### 💻 Implementação Não-Intrusiva
1. **Configuração do `ObservationRegistry`**:
   Registrar handlers para propagação automática e logging estruturado:
   ```java
   @Configuration
   public class ObservationConfig {
       @Bean
       public ObservationRegistryCustomizer<ObservationRegistry> observationRegistryCustomizer() {
           return registry -> registry.observationConfig().observationHandler(new LoggingObservationHandler());
       }
   }
   ```
2. **Propagação Automática em Mensageria e Feign**:
   O Spring Boot 3 + Spring Cloud OpenFeign e Spring Kafka injetam automaticamente o cabeçalho W3C `traceparent` nas mensagens e requisições HTTP sem nenhuma linha de código adicional.

---

## 8. Alarmística In-App & SLA Guards Não-Intrusivos

### 🎯 O que Metrificar
- **Violação de SLAs por Subprocesso/Passo**: Detecção em milissegundos de APIs lentas antes que o disjuntor precise abrir.
- **Incidentes por Componente**: Quantificação de incidentes agregados por alvo.
- **Desacoplamento de Canais**: Roteamento de alertas para múltiplos canais (Log, Webhook, Prometheus).

### 📦 Estrutura e Classes
- [`AlertDispatcher`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/alerting/AlertDispatcher.java): Centraliza a emissão de eventos.
- [`AlertingProperties`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/alerting/AlertingProperties.java): Declarativo no `application.yml`.
- [`FlowTrackingAspect`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/flow/FlowTrackingAspect.java): SLA Guard interceptor.

### ⚙️ Configuração (`application.yml`)
```yaml
app:
  observability:
    alerting:
      enabled: true
      flow-sla-thresholds:
        "GET /api/v1/orchestrator/users/{userId}": 1800
        default: 2500
      step-sla-thresholds:
        "API Customer (GET /customers/{userId})": 1000
        "API Billing (GET /billing/{userId})": 800
        "API Notification (POST /notifications)": 600
        default: 1500
```

### 💻 Como Funciona a Detecção
Ao término de cada step anotado com `@TrackStep`:
```java
long elapsedMs = stepDurationNanos / 1_000_000;
long thresholdMs = alertingProperties.resolveStepThreshold(stepName);
if (elapsedMs > thresholdMs) {
    alertDispatcher.dispatch(new AlertEvent(
        AlertType.INTEGRATION_LATENCY_SLA_BREACH,
        AlertSeverity.WARNING,
        "FlowStep",
        stepName,
        String.format("Latência do subprocesso '%s' (%d ms) excedeu o SLA (%d ms).", stepName, elapsedMs, thresholdMs)
    ));
}
```

### 📊 Métricas Chave & Consultas PromQL
- **Métrica Counter**: `alerts_triggered_total{type, severity, source, target}`
- **Frequência de Alertas por Segundo**:
  ```promql
  sum(rate(alerts_triggered_total[1m])) by (type, severity)
  ```
- **Incidentes Acumulados por Alvo**:
  ```promql
  sum(increase(alerts_triggered_total[15m])) by (target)
  ```

---

## 📋 Resumo Consolidado de Boas Práticas

1. **Nunca injete bibliotecas de observabilidade em Services**: Anote controllers, interfaces Feign, producers e consumers.
2. **Utilize AOP de Alta Precedência (`@Order(1)`)**: Garante que o cronômetro englobe retries, circuit breakers e serialização.
3. **Controle a Cardinalidade**: Em tags do Micrometer, use apenas valores categóricos finitos. Deixe IDs variáveis (`userId`, `orderId`) para os spans do Tracing (Jaeger) ou expressões SpEL avaliadas com cuidado.
4. **Calcule Lag no Broker, não na JVM**: Evita métricas cegas durante travamentos de consumidores.
5. **Opere em Duas Camadas de Alerta**:
   - Camada In-App (sub-segundo para disjuntores e estouros de SLA pontuais).
   - Camada Plataforma Prometheus (agregação temporal sobre sliding windows de percentis p95/p99).
6. **Consultas para Recursos Específicos e Variáveis Grafana**:
   - Para consultar um recurso específico (um único endpoint, uma fila específica, um disjuntor isolado) ou criar dropdowns dinâmicos no Grafana via `label_values()`, consulte a [Seção 6 do Catálogo Canônico de Métricas e Queries PromQL](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/CATALOGO_DE_METRICAS_E_QUERIES_DASHBOARD.md#6-guia-avançado-consultas-para-recursos-específicos-label-matchers--variáveis-grafana).
