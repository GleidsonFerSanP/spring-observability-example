# Guia de Observabilidade e Métricas

Este documento detalha o desenho técnico de observabilidade adotado no projeto, explicando os padrões de código, anotações customizadas e consultas PromQL utilizadas no Grafana.

---

## 🧩 1. Aspecto Não-Intrusivo com SpEL (`@ObservationTag`)

### O Problema Resolvido
Em implementações ingênuas de observabilidade, desenvolvedores frequentemente injetam o `ObservationRegistry` diretamente nas classes de serviço e controllers, poluindo a lógica de negócio com código de infraestrutura:
```java
// Anti-padrão: Código de negócio poluído
Observation.createNotStarted("operation", registry)
    .lowCardinalityKeyValue("billing_type", billing.getType())
    .observe(() -> ...);
```

Além disso, tentar capturar o valor de retorno de um método intermediário em um serviço complexo por meio de AOP tradicional esbarra no fato de que variáveis internas não são acessíveis via JoinPoint.

### A Solução: Interceptação nas Bordas (Feign Clients e Consumers)
Movemos as anotações customizadas para as **bordas da arquitetura** (nas interfaces dos clientes Feign e métodos consumidores):
- Na interface [`BillingClient`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/integration/BillingClient.java), o `BillingDto` **é** o valor de retorno.
- Criamos a anotação [`@ObservationTag`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/ObservationTag.java) e o aspecto [`SpelObservationAspect`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/SpelObservationAspect.java).

```java
@FeignClient(name = "billing-service", url = "${app.integrations.billing.url}")
public interface BillingClient {

    @ObservationTag(key = "client", expression = "'billing'")
    @ObservationTag(key = "billing_type", expression = "#result?.billingType()?.name()")
    @CircuitBreaker(name = "billing-service")
    @GetMapping("/billing/accounts/{userId}")
    BillingDto getBillingInfo(@PathVariable("userId") String userId);
}
```

O aspecto intercepta a chamada, injeta variáveis de entrada (`#userId`) antes da execução e, após o retorno, avalia expressões que dependem de `#result` com segurança contra `NullPointerException`, anexando as tags diretamente à observação ativa do Micrometer.

---

## ⚡ 2. Circuit Breakers Granulares

Em vez de aplicar um único `@CircuitBreaker(name = "orchestrator")` no serviço central — o que mascararia qual integração falhou e derrubaria todo o orquestrador —, cada cliente externo possui seu próprio disjuntor:
- `customer-service`: monitora chamadas para `/customers/{userId}`
- `billing-service`: monitora chamadas para `/billing/accounts/{userId}`
- `notification-service`: monitora chamadas para `/notifications`

Isso permite:
1. Identificar visualmente no Grafana exatamente qual client abriu.
2. Ativar fallbacks isolados por serviço.
3. Evitar falhas em cascata no fluxo principal.

---

## 📬 3. Descoberta Dinâmica de Filas SQS (`SqsMetricsBinder`)

Para evitar *hardcoding* de nomes de filas no código Java, a classe [`SqsMetricsBinder`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/SqsMetricsBinder.java) implementa a interface `MeterBinder` do Micrometer:
1. Executa um agendamento assíncrono a cada 10 segundos invocando `sqsAsyncClient.listQueues()`.
2. Para cada fila retornada pela AWS/LocalStack, registra dinamicamente um `Gauge` no Micrometer sob a métrica `sqs_queue_depth` com a tag `queue="<nome-da-fila>"`.
3. Dessa forma, novas filas criadas em tempo de execução são descobertas e plotadas automaticamente nos dashboards sem necessidade de alteração de código ou re-deploy.

---

## 📊 4. Especificação dos Painéis do Grafana (`grafana-dashboard.json`)

O painel está organizado em 4 seções estratégicas:

### Painel 1: ⏱ Latência Ponta a Ponta (E2E)
- **Objetivo**: Medir o tempo total do fluxo `fetchAndProvisionUserProfile`.
- **Média (s)**:
  ```promql
  sum(rate(user_profile_provision_seconds_sum[1m])) / sum(rate(user_profile_provision_seconds_count[1m]))
  ```
- **Pico Máximo (s)**:
  ```promql
  max(user_profile_provision_seconds_max)
  ```

### Painel 2: 🔪 Fatias de Latência por Integração (Feign)
- **Objetivo**: Isolar o tempo gasto em cada serviço externo para identificar gargalos.
- **Consulta**:
  ```promql
  sum(rate(resilience4j_circuitbreaker_calls_seconds_sum{group="none", name=~"customer-service|billing-service|notification-service"}[1m])) by (name)
  /
  sum(rate(resilience4j_circuitbreaker_calls_seconds_count{group="none", name=~"customer-service|billing-service|notification-service"}[1m])) by (name)
  ```

### Painéis 3, 4 e 5: 🚦 Saúde das Integrações (Cards Stat)
- **Estado dos Circuit Breakers**:
  ```promql
  resilience4j_circuitbreaker_state{group="none", name=~"customer-service|billing-service|notification-service", state="closed"}
  ```
  *(1 = 🟢 FECHADO, 0 = 🔴 ABERTO)*
- **Taxa de Erros (%)**:
  ```promql
  sum(rate(resilience4j_circuitbreaker_calls_seconds_count{group="none", kind="failed"}[5m])) by (name)
  /
  (sum(rate(resilience4j_circuitbreaker_calls_seconds_count{group="none", kind="successful"}[5m])) by (name) + sum(rate(resilience4j_circuitbreaker_calls_seconds_count{group="none", kind="failed"}[5m])) by (name)) * 100
  ```
- **Throughput (req/s)**:
  ```promql
  sum(rate(resilience4j_circuitbreaker_calls_seconds_count{group="none", name=~"customer-service|billing-service|notification-service"}[1m])) by (name)
  ```

### Painel 1 e 2: 🥧 Decomposição do Tempo por Fluxo de Entrada (Entrypoints)
Em vez de misturar todas as operações da arquitetura em um gráfico genérico, **cada fluxo de entrada (entrypoint) possui sua própria pizza**, onde 100% da pizza representa o tempo total de resposta daquele fluxo e as fatias representam estritamente o tempo consumido por seus subprocessos:

#### 1. Fluxo Síncrono REST: `GET /api/v1/orchestrator/users/{userId}`
- **Duração Total**: Medida pela observação `user_profile_provision_seconds`.
- **Fatias**:
  - `API Customer (GET /customers/{userId})`:
    ```promql
    sum(rate(resilience4j_circuitbreaker_calls_seconds_sum{group="none", name="customer-service"}[1m])) / sum(rate(resilience4j_circuitbreaker_calls_seconds_count{group="none", name="customer-service"}[1m]))
    ```
  - `API Billing (GET /billing/accounts/{userId})`:
    ```promql
    sum(rate(resilience4j_circuitbreaker_calls_seconds_sum{group="none", name="billing-service"}[1m])) / sum(rate(resilience4j_circuitbreaker_calls_seconds_count{group="none", name="billing-service"}[1m]))
    ```
  - `API Notificação (POST /notifications)`:
    ```promql
    sum(rate(resilience4j_circuitbreaker_calls_seconds_sum{group="none", name="notification-service"}[1m])) / sum(rate(resilience4j_circuitbreaker_calls_seconds_count{group="none", name="notification-service"}[1m]))
    ```
  - `Processamento Interno & Regras de Negócio`:
    Tempo residual do fluxo (Total E2E - Soma das chamadas externas).

#### 2. Fluxo Orientado a Eventos: Consumidor Kafka (`user-registration-topic`)
- **Duração Total**: Medida pelo listener Kafka `spring_kafka_listener_seconds`.
- **Fatias**:
  - `Provisionamento de Perfil (APIs Feign)`: Tempo gasto chamando os microsserviços.
  - `Publicação SQS Auditoria (user-audit-queue)`: Tempo de publicação na fila SQS.
  - `Publicação Kafka Cobrança (billing-events-topic)`: Tempo de publicação no tópico Kafka.
  - `Publicação SQS Boas-Vindas (welcome-email-queue)`: Tempo de publicação na fila SQS.
  - `Deserialização & Regras do Consumidor`: Tempo interno de overhead do listener.

### Painéis 9 e 10: 📤📥 Vazão de Mensageria
- **Taxa de Produção (msg/s)**:
  ```promql
  sum(rate(messaging_produce_seconds_count[1m])) by (method)
  ```
- **Taxa de Consumo (msg/s)**:
  ```promql
  sum(rate(messaging_consume_seconds_count[1m])) by (method)
  ```
