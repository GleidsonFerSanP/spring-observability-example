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

## 🔗 4. Catálogo Canônico de Métricas e Queries PromQL

Para consultar a especificação completa de **todas as 15 consultas PromQL**, fórmulas matemáticas, mapeamentos de valor e os detalhes técnicos de cada painel do Grafana, consulte o documento dedicado:
👉 **[Catálogo de Métricas Customizadas e Consultas PromQL do Dashboard](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/CATALOGO_DE_METRICAS_E_QUERIES_DASHBOARD.md)**.

---

## 🥧 5. Decomposição de Latência por Fluxo de Entrada (`@TrackFlow` e `@TrackStep`)

### O Problema do Gráfico de Pizza Global
Em arquiteturas de microsserviços, tentar criar um gráfico de pizza somando métricas globais de clientes HTTP (como `resilience4j_circuitbreaker_calls_seconds`) e mensageria gera distorções graves:
1. **Contaminação de Contexto**: Se tanto a API REST `GET /users/{userId}` quanto o consumidor assíncrono Kafka invocam o `customer-service`, uma métrica global de Feign somará o tempo de ambos os fluxos, inviabilizando saber quanto tempo o fluxo REST consumiu.
2. **Falta de Fechamento Matemático (100%)**: Métricas de clientes externos cobrem apenas o tempo das requisições de rede. O tempo de processamento interno da aplicação (serialização, regras de negócio, transformações, banco) ficava invisível.
3. **Fluxos Heterogêneos**: Um endpoint REST possui integrações diferentes de um consumidor de fila. Cada **fluxo de entrada (entrypoint)** precisa de sua própria pizza isolada.

### A Solução: Arquitetura `FlowContext` via ThreadLocal e AOP
Implementamos um mecanismo desacoplado e não-intrusivo para rastrear o ciclo de vida completo de cada requisição:

1. **`@TrackFlow("<Nome do Fluxo>")`**:
   - Anotado exclusivamente nos pontos de entrada do sistema:
     - Controller REST: `@TrackFlow("GET /api/v1/orchestrator/users/{userId}")`
     - Consumer Kafka: `@TrackFlow("Kafka Consumer: user-registration-topic")`
   - Inicia uma pilha de contexto no [`FlowContext`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/flow/FlowContext.java) registrando `startNanos`.

2. **`@TrackStep("<Nome da Fatia>")`**:
   - Anotado nas interfaces das bordas externas:
     - `CustomerClient`: `@TrackStep("API Customer (GET /customers/{userId})")`
     - `BillingClient`: `@TrackStep("API Billing (GET /billing/accounts/{userId})")`
     - `NotificationClient`: `@TrackStep("API Notificação (POST /notifications)")`
     - `KafkaUserProducer`: `@TrackStep("Publicação Kafka (...)")`
     - `SqsUserProducer`: `@TrackStep("Publicação SQS (...)")`
   - Interceptado por [`FlowTrackingAspect`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/flow/FlowTrackingAspect.java), que mede a duração do join point e registra a fatia no `FlowContext` corrente da thread.

3. **Cálculo da Fatia Residual ("Processamento Interno & Regras")**:
   Ao finalizar o método do entrypoint (`joinPoint.proceed()`), o interceptor executa:
   $$\text{internalNanos} = \max(0, \text{totalNanos} - \sum \text{stepNanos})$$
   Em seguida, publica no `MeterRegistry` do Micrometer:
   - `flow_slice_duration_seconds{flow="...", step="..."}` para cada subprocesso.
   - `flow_slice_duration_seconds{flow="...", step="Processamento Interno & Regras"}` para o overhead interno.
   - `flow_total_duration_seconds{flow="..."}` para a duração end-to-end do fluxo.

---

## 📈 6. Monitoramento Autoritativo de Kafka Lag (`KafkaLagMetricsBinder`)

Para garantir que o lag do Kafka seja monitorado com precisão no Grafana mesmo sob cargas extremas ou sem consumidores ativos, implementamos [`KafkaLagMetricsBinder`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/src/main/java/com/gleidsonfersanp/observability/observability/KafkaLagMetricsBinder.java):
1. Cria uma instância de `AdminClient` do Apache Kafka a partir das configurações do Spring.
2. A cada 5 segundos, consulta os offsets correntes com `adminClient.listConsumerGroupOffsets(...)`.
3. Consulta o último offset das partições no broker via `adminClient.listOffsets(OffsetSpec.latest())`.
4. Calcula a diferença real:
   $$\text{Lag} = \text{Offset}_{\text{broker}} - \text{Offset}_{\text{consumer}}$$
5. Publica como Gauge do Micrometer: `kafka_consumer_lag_records{topic="...", group="..."}`.

---

## 📊 7. Especificação dos Painéis do Grafana (`grafana-dashboard.json`)

O painel foi desenhado visando máxima legibilidade e ergonomia, sem truncamento de textos:

### Linha 1: 🥧 Decomposição de Latência E2E por Fluxo (Gráficos de Pizza)
Cada gráfico representa 100% do tempo de um fluxo específico, dividido estritamente em suas fatias internas:

#### Painel 1.1: `🥧 Entrypoint REST Síncrono: GET /users/{userId}`
- **Tipo**: Pie Chart (Donut)
- **Consulta PromQL**:
  ```promql
  sum(rate(flow_slice_duration_seconds_sum{flow="GET /api/v1/orchestrator/users/{userId}"}[1m])) by (step)
  ```
- **Fatias exibidas**:
  - `API Customer (GET /customers/{userId})`
  - `API Billing (GET /billing/accounts/{userId})`
  - `API Notificação (POST /notifications)`
  - `Processamento Interno & Regras`

#### Painel 1.2: `🥧 Entrypoint Assíncrono: Consumidor Kafka (user-registration-topic)`
- **Tipo**: Pie Chart (Donut)
- **Consulta PromQL**:
  ```promql
  sum(rate(flow_slice_duration_seconds_sum{flow="Kafka Consumer: user-registration-topic"}[1m])) by (step)
  ```
- **Fatias exibidas**:
  - `API Customer (GET /customers/{userId})`
  - `API Billing (GET /billing/accounts/{userId})`
  - `API Notificação (POST /notifications)`
  - `Publicação SQS (user-audit-queue)`
  - `Publicação SQS (welcome-email-queue)`
  - `Publicação Kafka (billing-events-topic)`
  - `Processamento Interno & Regras`

---

### Linha 2: 🚦 Saúde das Integrações e Circuit Breakers
Três cards largos (`w: 8`, `h: 4`) para evitar qualquer truncamento de texto:

#### Painel 2.1: `Circuit Breaker: Customer Service`
- **Consulta**:
  ```promql
  resilience4j_circuitbreaker_state{name="customer-service", state="closed"}
  ```
- **Formatação de Valor**: `1 -> 🟢 FECHADO` (Verde), `0 -> 🔴 ABERTO` (Vermelho).

#### Painel 2.2: `Circuit Breaker: Billing Service`
- **Consulta**:
  ```promql
  resilience4j_circuitbreaker_state{name="billing-service", state="closed"}
  ```
- **Formatação de Valor**: `1 -> 🟢 FECHADO` (Verde), `0 -> 🔴 ABERTO` (Vermelho).

#### Painel 2.3: `Circuit Breaker: Notification Service`
- **Consulta**:
  ```promql
  resilience4j_circuitbreaker_state{name="notification-service", state="closed"}
  ```
- **Formatação de Valor**: `1 -> 🟢 FECHADO` (Verde), `0 -> 🔴 ABERTO` (Vermelho).

---

### Linha 3: ⏱️ Latências e Taxas de Erro (Time Series)

#### Painel 3.1: `⏱ Latência Ponta a Ponta (E2E) - Média vs Máxima`
- **Média (s)**:
  ```promql
  sum(rate(flow_total_duration_seconds_sum{flow="GET /api/v1/orchestrator/users/{userId}"}[1m]))
  /
  sum(rate(flow_total_duration_seconds_count{flow="GET /api/v1/orchestrator/users/{userId}"}[1m]))
  ```
- **Pico Máximo (s)**:
  ```promql
  max(flow_total_duration_seconds_max{flow="GET /api/v1/orchestrator/users/{userId}"})
  ```

#### Painel 3.2: `🔥 Taxa de Falhas dos Circuit Breakers (%)`
- **Consulta**:
  ```promql
  sum(rate(resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}[1m])) by (name)
  /
  (sum(rate(resilience4j_circuitbreaker_calls_seconds_count{kind="successful"}[1m])) by (name)
   + sum(rate(resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}[1m])) by (name)) * 100
  ```

---

### Linha 4: 📬 Mensageria e Filas (Kafka & SQS)

#### Painel 4.1: `📦 Profundidade das Filas SQS (sqs_queue_depth)`
- **Consulta**:
  ```promql
  sqs_queue_depth
  ```
- Plota automaticamente todas as filas descobertas dinamicamente pelo `SqsMetricsBinder` (`user-audit-queue`, `welcome-email-queue`, etc.).

#### Painel 4.2: `🐢 Kafka Consumer Lag (kafka_consumer_lag_records)`
- **Consulta**:
  ```promql
  kafka_consumer_lag_records
  ```
- Plota o lag real calculado diretamente no broker pelo `KafkaLagMetricsBinder` para os tópicos e grupos ativos.

#### Painel 4.3: `⚡ Throughput de Mensageria (Produção vs Consumo)`
- **Produção**:
  ```promql
  sum(rate(messaging_produce_seconds_count[1m])) by (method)
  ```
- **Consumo**:
  ```promql
  sum(rate(messaging_consume_seconds_count[1m])) by (method)
  ```

---

## 🗄️ 8. Observabilidade de Banco de Dados Relacional (PostgreSQL / HikariCP)

A aplicação foi estendida para garantir total visibilidade sobre o comportamento de chamadas a bancos de dados relacionais e da gerência do pool de conexões (HikariCP). Isso permite analisar de perto a latência de consultas e também agir de forma preditiva sobre o uso e esgotamento do pool.

### 7.1. O que foi monitorado?

1. **Métricas de Pool de Conexões (HikariCP)**: Habilitado nativamente via Actuator através da configuração `spring.datasource.hikari.pool-name: observability-hikari-pool`.
2. **Tempo de Execução e Métricas do Hibernate**: Habilitado via injeção de estatísticas `hibernate.generate_statistics: true` e `session_scoped_interceptor: org.hibernate.resource.jdbc.spi.StatementInspector`. 

### 7.2. Principais Métricas Exportadas para o Prometheus

| Métrica Prometheus | O que indica? | Sinal de Alerta |
| :--- | :--- | :--- |
| `hikaricp_connections_active` | Número de conexões atualmente atreladas a uma transação (em uso). | Crescimento contínuo pode indicar *slow queries* prendendo conexões. |
| `hikaricp_connections_idle` | Número de conexões abertas com o banco aguardando serem utilizadas. | Se for sempre zero, seu pool base pode estar subdimensionado. |
| `hikaricp_connections_pending` | Threads aguardando a liberação de uma conexão (fila de espera). | Qualquer valor `> 0` significa latência extra introduzida na aplicação. |
| `hikaricp_connections_timeout_total` | Quantidade de vezes em que o tempo de espera máximo (`connection-timeout`) na fila do pool estourou. | Valor crescente causa erros 500 no cliente. Requer atenção imediata (exaustão do pool). |
| `hikaricp_connections` | Total de conexões ativas e ociosas no momento. | Se mantiver travado no `maximum-pool-size`, indica alta contenção. |

### 7.3. Métricas adicionais e Tracing (O que veremos no Grafana e Jaeger)

A configuração permite que no Jaeger todo acesso ao banco seja tracejado. Um `Span` do banco de dados relacional mostrará claramente o comando executado e a duração. No lado das métricas, os tempos de cada *slow query* e falha de timeout podem disparar alertas customizados antes que a exaustão se torne completa.

---

## 🛑 8. Tracing Hierárquico e Ponto Exato de Interrupção (Dead Stop Breakdown)

Para diagnosticar imediatamente **onde e por que um fluxo de negócio foi interrompido sem precisar vasculhar logs brutos**, a camada de observabilidade integra Tracing distribuído e métricas de Dead Stop:

### 8.1. Árvore Hierárquica de Spans via Micrometer Observation
1. **Span Raiz (`@TrackFlow`)**:
   - Cria o contexto da transação (ex: `flow.get.api.v1.orchestrator.users.userid`).
   - Propaga o `traceparent` via W3C Trace Context.
2. **Spans Filhos (`@TrackStep`)**:
   - Cria spans aninhados para cada etapa do subprocesso:
     - `step.api.customer.get.customers.userid`
     - `step.api.billing.get.billing.accounts.userid`
     - `step.api.notificacao.post.notifications`
     - `step.publicacao.kafka...`
3. **Identificação de Erro no Span**:
   - Quando uma exceção é lançada em um step (ex: `IntegrationServerException` HTTP 500 do Billing), o `FlowTrackingAspect` executa `stepObservation.error(t)` antes que o disjuntor capture o erro no fallback.
   - O span da etapa é marcado em **vermelho** com tags `error=true`, `step.status=FAILED`, `error.class` e `error.message`.

### 8.2. Métrica Canônica de Interrupção (`flow_interruption_total`)
No momento exato da quebra de qualquer etapa, é incrementado:
```promql
flow_interruption_total{flow="...", failed_step="...", error_type="..."}
```

### 8.3. Painéis de Dead Stop no Grafana (`grafana-dashboard.json`)
- **Painel 16: Ponto Exato de Interrupção de Fluxos**: Gráfico de barras horizontais ordenado por volume de quebras, permitindo bater o olho e ver qual integração é o gargalo.
  ```promql
  sum(increase(flow_interruption_total[15m])) by (failed_step, error_type)
  ```
- **Painel 17: Auditoria de Quebras por Step e Causa Raiz**: Tabela instantânea detalhando o fluxo afetado, o step exato onde ocorreu a quebra e a classe da exceção causadora.
  ```promql
  sum by (flow, failed_step, error_type) (flow_interruption_total)
  ```
- **Navegação com Exemplars (Métrica -> Trace)**: Os gráficos de latência e erro do Prometheus possuem marcações clicáveis (Exemplars) com o `trace_id`, abrindo a árvore do Jaeger diretamente na interface do Grafana.

