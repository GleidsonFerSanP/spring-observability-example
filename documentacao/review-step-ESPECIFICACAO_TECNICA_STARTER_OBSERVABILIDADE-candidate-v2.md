Revisei o `documentacao/ESPECIFICACAO_TECNICA_STARTER_OBSERVABILIDADE.md`, as implementações atuais de `FlowContext`, `FlowTrackingAspect`, correlação e `LegLoggingAspect`, e também a evolução recente da `main`. O documento atual registra muito bem **como o laboratório chegou até aqui**, mas eu não o usaria como especificação final do starter sem algumas correções arquiteturais.

A principal é exatamente a que nossa discussão revelou: hoje o lab considera `total - Σsteps` como fechamento matemático. Isso funciona para o cenário sequencial testado, mas **não é matematicamente correto de forma geral** com `CompletableFuture`, spans aninhados, retries e concorrência. Além disso, o repo já ganhou capacidades depois da spec — interruption tracking, exemplars, legs, Loki, masking, correlation ID e testes E2E — que precisam virar requisitos de primeira classe.

Abaixo está a versão que eu usaria para **substituir conceitualmente** o documento atual. Ainda a classificaria como **Candidate Architecture v2**, porque deixei explícitas no final algumas decisões que precisamos validar antes de chamar de arquitetura definitiva.

# Especificação Técnica — Corporate Flow Observability Spring Boot Starter

## Candidate Architecture v2

### Observabilidade não intrusiva, Flow Intelligence, Latency Attribution, Distributed Tracing, Metrics, Logs, Resilience e Messaging

---

# 1. Objetivo

Construir um Spring Boot Starter corporativo reutilizável, denominado provisoriamente:

```text
observability-spring-boot-starter
```

cujo objetivo seja transformar aplicações Spring Boot em componentes operacionalmente observáveis com mínima intervenção manual.

O starter deverá permitir responder, para qualquer fluxo relevante da aplicação:

> Esta operação demorou 1,2 segundo. Onde esse tempo foi gasto, quais dependências participaram, o que ocorreu em paralelo, qual dependência determinou a latência percebida, houve retry, timeout, Circuit Breaker, espera em thread pool, backlog de mensageria ou outro fator de degradação?

A solução deverá fornecer de maneira integrada:

```text
Metrics
Tracing
Structured Logs
Flow decomposition
Latency attribution
Critical path analysis
Parallelism visibility
Resilience visibility
Messaging visibility
Resource saturation
Correlation
Alerting signals
SLO/SLI support
```

sem espalhar chamadas manuais de `MeterRegistry`, `Tracer`, `Timer`, `Span` ou APIs específicas de vendors pelo código de negócio.

---

# 2. Origem e aprendizados do laboratório

Esta arquitetura deriva diretamente dos experimentos realizados no repositório:

```text
GleidsonFerSanP/spring-observability-example
```

O laboratório já demonstrou com sucesso:

- decomposição de fluxo por entrypoint;
- `@TrackFlow`;
- `@TrackStep`;
- AOP não intrusivo;
- extração dinâmica de tags via SpEL;
- isolamento de métricas por fluxo;
- Circuit Breakers Resilience4j granulares;
- alarmística reativa;
- Kafka Consumer Lag via `AdminClient`;
- SQS Queue Depth via AWS SDK;
- métricas HikariCP;
- distributed tracing;
- exemplars;
- navegação metric → trace;
- flow interruption tracking;
- legs de comunicação;
- logs estruturados;
- masking de payload com SpEL;
- integração Loki;
- correlation ID;
- propagação de contexto;
- appenders assíncronos;
- testes de integração/E2E;
- chaos testing;
- abstração de vendor.

Esses resultados devem ser preservados.

Entretanto, algumas decisões corretas para o laboratório precisam evoluir antes da extração para um starter corporativo.

---

# 3. Correção arquitetural fundamental: FlowContext v1 não é suficiente

A implementação atual utiliza:

```java
ThreadLocal<Deque<FlowContext>>
```

e calcula:

```text
internal =
    flowTotal
    - Σ(stepDuration)
```

Esse cálculo é correto apenas quando os steps relevantes são essencialmente sequenciais e não existe sobreposição temporal significativa.

Exemplo sequencial:

```text
FLOW = 1200ms

A = 200ms
B = 300ms
C = 400ms
Internal = 300ms

200 + 300 + 400 + 300 = 1200ms
```

Correto.

Porém:

```text
                ┌── API A 300ms ──────┐
Request ────────┼── API B 600ms ──────┼── Response
                └── API C 400ms ──────┘
```

As chamadas foram executadas em paralelo.

Temos:

```text
A = 300ms
B = 600ms
C = 400ms

Σ work = 1300ms
```

mas o usuário esperou aproximadamente:

```text
600ms
```

Portanto:

```text
flowTotal - Σsteps
```

produziria:

```text
600 - 1300 = -700
```

e o `max(0, ...)` esconderia o problema em vez de resolvê-lo.

Consequentemente, a arquitetura final NÃO deverá tratar:

```text
duration de span
```

como sinônimo de:

```text
contribution para wall-clock latency
```

São conceitos diferentes.

---

# 4. Três dimensões temporais obrigatórias

Todo Flow deverá possuir pelo menos três interpretações temporais.

## 4.1 Wall-clock Duration

Tempo percebido externamente.

```text
flow.wall_clock.duration
```

Exemplo:

```text
HTTP request → HTTP response

1.200 ms
```

---

## 4.2 Work Duration

Soma do trabalho executado pelos subprocessos observados.

```text
flow.work.duration
```

Em operações concorrentes é perfeitamente válido:

```text
work.duration > wall_clock.duration
```

Exemplo:

```text
Flow                = 600ms

Customer API        = 300ms
Fraud API           = 600ms
Score API           = 400ms

Work                = 1300ms
```

Isso não é erro.

Significa paralelismo.

---

## 4.3 Attributed Wall-Clock Duration

Tempo do Flow atribuído semanticamente aos componentes que realmente contribuíram para a latência percebida:

```text
flow.component.attributed.duration
```

Essa é a métrica destinada ao gráfico de pizza de composição de latência.

Invariante:

```text
Σ attributed component duration
+
unattributed duration
≈
flow wall-clock duration
```

dentro de uma tolerância técnica mínima.

---

# 5. O gráfico de pizza não deverá usar duração bruta dos spans

O gráfico:

```text
Latency Composition
```

deverá responder:

> Dos 1,2 segundo percebidos pelo consumidor, quem contribuiu para esse tempo?

Ele NÃO deverá simplesmente fazer:

```text
SUM(span.duration)
```

nem:

```text
SUM(@TrackStep duration)
```

quando houver sobreposição.

A métrica utilizada será:

```text
flow.component.attributed.duration
```

e não:

```text
flow.component.work.duration
```

---

# 6. Visualizações obrigatórias de Flow

Um Flow deverá possuir três visualizações complementares.

## 6.1 Latency Composition

Pizza com exatamente o tempo percebido.

Exemplo:

```text
POST /payments
Average Wall Clock = 1.2s

Business logic         270ms
Customer API           180ms
Fraud API              430ms
Database               190ms
Kafka                    40ms
Framework/Internal       80ms
Unattributed              10ms
                       -------
                        1200ms
```

Objetivo:

```text
"Quem está comendo minha latência?"
```

---

## 6.2 Work Distribution

Mostra quanto trabalho realmente aconteceu.

Pode ultrapassar 100% do Flow.

Exemplo:

```text
Flow wall-clock           1200ms

Customer API               180ms
Fraud API                  650ms
Score API                  420ms
History API                310ms
Database                   190ms
Kafka                       40ms
Business                    270ms
                         --------
Total work                2060ms
```

Objetivo:

```text
"Onde o sistema está gastando recursos?"
```

---

## 6.3 Timeline / Waterfall / Critical Path

Representação temporal:

```text
POST /payments                                      1200ms
│
├─ validation               ███                      40
│
├─ customer-api                ███████              180
│
├─ verification                  █████████████
│    ├─ fraud-api                █████████████       600
│    ├─ score-api                █████████           420
│    └─ history-api              ██████              310
│
├─ database                                  █████   190
│
├─ kafka                                          ██ 40
│
└─ response                                         ██
```

Deverá indicar explicitamente:

```text
parallel
critical path
retry
timeout
fallback
queue wait
```

Objetivo:

```text
"Como essa execução realmente aconteceu?"
```

Nenhuma das três visões substitui as outras.

---

# 7. Modelo Flow Execution v2

O `FlowContext` deixa de ser conceitualmente apenas:

```text
ThreadLocal + Map<step, duration>
```

e passa a representar uma execução temporal.

Modelo lógico:

```text
FlowExecution
├── flowId
├── flowName
├── traceId
├── correlationId
├── start
├── end
├── outcome
├── entrypoint
│
└── StepExecution[]
      ├── stepId
      ├── parentStepId
      ├── component
      ├── componentType
      ├── start
      ├── end
      ├── outcome
      ├── asynchronous
      ├── retryAttempt
      ├── attributes
      └── children[]
```

`FlowExecution` deverá ser seguro para concorrência.

Não poderá depender da hipótese:

```text
uma requisição = uma única thread
```

---

# 8. Context propagation

`ThreadLocal` continuará podendo existir como mecanismo de acesso conveniente em código imperativo, mas NÃO será a fonte única de verdade.

A propagação deverá utilizar os mecanismos de Micrometer Context Propagation e/ou contexto de tracing.

Micrometer fornece `ThreadLocalAccessor`, `ContextSnapshot` e mecanismos para capturar/restaurar contexto ao atravessar threads e executors. :chatgpt-content-reference{index="0"}

O starter deverá suportar:

```text
Spring MVC
@Async
TaskExecutor
ExecutorService gerenciado
CompletableFuture com executor gerenciado
Reactor
Kafka
SQS
HTTP
gRPC
```

Exemplo:

```text
Request
trace=AAA
correlation=CCC
flow=payment

       |
       +---- Future A
       |       trace=AAA
       |       flow=payment
       |
       +---- Future B
               trace=AAA
               flow=payment
```

### Limitação explícita

Um starter Spring não deve prometer propagação automática confiável para:

```java
CompletableFuture.supplyAsync(...)
```

utilizando arbitrariamente o `ForkJoinPool.commonPool()` sem qualquer integração adicional.

Para garantia de contexto, deverá ser utilizado:

```text
TaskDecorator
ContextSnapshot
Executor instrumentado
Spring @Async configurado
```

ou instrumentação via agente quando adotada pela plataforma.

O starter deverá fornecer um executor/decorator padronizado para esse propósito.

---

# 9. Latency Attribution Engine

Esta é a capacidade central que diferencia o starter de uma simples coleção de métricas.

Entrada:

```text
FlowExecution + StepExecution intervals
```

Exemplo:

```text
0        200        400        600

A        ├──────────┤
B             ├────────────────┤
C                  ├───────┤
```

O engine deverá construir todas as fronteiras temporais:

```text
start/end A
start/end B
start/end C
```

e transformar a timeline em intervalos mutuamente exclusivos:

```text
[0,100]
[100,200]
[200,300]
...
```

Para cada intervalo:

```text
activeSteps
parallelismLevel
criticalOwner
attribution
```

Deverá calcular:

```text
wallClockDuration
workDuration
coveredDuration
parallelOverlapDuration
maxConcurrency
criticalPathDuration
unattributedDuration
componentAttributedDuration
```

---

# 10. Critical Path

O sistema deverá identificar os branches que efetivamente determinaram a duração do fluxo.

Exemplo:

```text
             ┌── customer 300ms ──┐
Request ─────┼── fraud    600ms ──┼──── response
             └── score    400ms ──┘
```

Nesse trecho:

```text
fraud-api
```

é o componente crítico.

Os outros serviços executaram trabalho, mas não aumentaram adicionalmente o wall-clock daquele bloco.

Dashboard:

```text
fraud-api

raw duration              600ms
attributed duration       600ms
critical path             YES
parallel                   YES
```

```text
score-api

raw duration              400ms
attributed duration         0ms naquele overlap
critical path             NO
parallel                   YES
```

A duração bruta continua disponível na Work Distribution.

---

# 11. Parallel Group

Para evitar conclusões enganosas, o modelo deverá também manter o conceito:

```text
Parallel Group
```

Exemplo:

```text
Parallel verification = 600ms

├─ fraud     600ms  CRITICAL
├─ score     400ms
└─ history   250ms
```

A interface poderá representar:

```text
Verification Parallel Group — 600ms
```

com drill-down dos participantes.

A plataforma poderá oferecer duas representações:

```text
critical-path attribution
```

ou:

```text
parallel-group attribution
```

mas deverá deixar claro qual semântica está sendo utilizada.

Nunca atribuir silenciosamente os mesmos 600ms a três componentes diferentes na pizza de wall-clock.

---

# 12. Unattributed Time

Métrica:

```text
flow.unattributed.duration
```

Representa tempo do Flow ainda não explicado por componentes instrumentados.

Exemplo:

```text
Flow total          1200ms
Covered             1110ms
Unattributed          90ms

Coverage             92.5%
```

Métrica complementar:

```text
flow.instrumentation.coverage
```

Essa métrica mede qualidade da instrumentação.

Uma aplicação pode estar saudável e ainda possuir baixa observabilidade.

O dashboard deverá tornar isso visível.

---

# 13. Entrypoints

O starter deverá reconhecer:

```text
REST MVC
WebFlux
Kafka consumers
SQS consumers
JMS
gRPC server
@Scheduled
batch jobs
custom handlers
```

A annotation:

```java
@TrackFlow("create-payment")
```

deverá fornecer principalmente semântica de negócio.

Porém o timer end-to-end HTTP idealmente deverá estar associado ao lifecycle real da requisição HTTP e não apenas ao corpo do método Controller.

Isso inclui potencialmente:

```text
filter processing
controller invocation
serialization
exception handling
response generation
```

Portanto:

```text
native framework observation
```

deve ser preferida como fonte de duração do entrypoint quando existir.

---

# 14. Princípio de instrumentação

Ordem preferencial:

```text
1. Native framework instrumentation
2. Observation customization/enrichment
3. AOP semantic instrumentation
4. Manual instrumentation
```

Não:

```text
"AOP para tudo"
```

O starter deverá evitar duplicar spans já criados por:

```text
Spring MVC
WebClient
RestClient
Feign
Kafka
JDBC
OTel instrumentation
```

AOP será especialmente útil para:

```text
Flow semantics
Business steps
Latency attribution
Dynamic business tags
Leg auditing
Custom operations
```

---

# 15. OpenTelemetry Semantic Conventions

HTTP, database, RPC e messaging deverão seguir OpenTelemetry Semantic Conventions sempre que possível.

As convenções HTTP atuais são estáveis. :chatgpt-content-reference{index="1"}

As convenções de messaging incluem conceitos específicos para producer, consumer, send, receive e process, bem como propagação de contexto e correlação producer/consumer. :chatgpt-content-reference{index="2"}

Como as convenções de messaging ainda possuem partes em desenvolvimento, a versão adotada deverá ser documentada e controlada pela plataforma para evitar mudança silenciosa de nomes de atributos. :chatgpt-content-reference{index="3"}

---

# 16. REST/HTTP Dependencies

Toda integração deverá fornecer:

```text
request count
error count
timeout count
duration
P50
P95
P99
concurrent calls
retry count
```

Dimensões permitidas:

```text
dependency
operation
http_method
route_template
outcome
```

Nunca:

```text
/customer/123
/customer/456
```

como dimensão.

Utilizar:

```text
/customer/{id}
```

---

# 17. Retry model

Retry deve distinguir:

```text
logical invocation
```

de:

```text
physical attempt
```

Exemplo:

```text
fraud-api logical call          1150ms
│
├─ attempt 1                    500ms TIMEOUT
├─ backoff                      100ms
├─ attempt 2                    400ms ERROR
├─ backoff                       50ms
└─ attempt 3                    100ms SUCCESS
```

O Latency Composition deverá considerar a duração lógica observada.

O trace poderá mostrar cada attempt separadamente.

Métricas:

```text
retry.calls
retry.attempts
retry.success_after_retry
retry.exhausted
retry.wait.duration
```

Isso permite identificar sistemas aparentemente saudáveis cuja latência está sendo mascarada por retries.

---

# 18. Circuit Breaker

Preservar a abordagem do laboratório de Circuit Breaker por dependência.

Não utilizar um único breaker para múltiplas integrações independentes.

Estado:

```text
CLOSED
OPEN
HALF_OPEN
FORCED_OPEN
DISABLED
```

Métricas deverão incluir, preferencialmente aproveitando as métricas nativas do Resilience4j:

```text
state
failure_rate
slow_call_rate
buffered_calls
successful_calls
failed_calls
not_permitted_calls
```

Resilience4j já fornece integração Micrometer para esses conceitos. :chatgpt-content-reference{index="4"}

Eventos:

```text
CLOSED -> OPEN
OPEN -> HALF_OPEN
HALF_OPEN -> CLOSED
```

devem gerar:

```text
structured event
trace event quando houver contexto
metric/counter
```

---

# 19. Fallback

Fallback deverá ser explicitamente observável.

Flow:

```text
SUCCESS
DEGRADED_FALLBACK
INTERRUPTED
TIMEOUT
CANCELLED
```

Exemplo:

```text
GET /profile

flow.status = DEGRADED_FALLBACK
failed_component = billing-api
fallback = cached-billing
```

Um HTTP 200 decorrente de fallback não poderá ser indistinguível de um processamento 100% saudável.

Essa capacidade já começou a ser construída no laboratório e deverá tornar-se contrato oficial.

---

# 20. Bulkhead, Rate Limiter e TimeLimiter

Métricas:

## Bulkhead

```text
available_permissions
max_concurrent_calls
active_calls
rejected_calls
wait_duration
```

## Thread-pool Bulkhead

```text
active_threads
pool_size
max_pool_size
queue_depth
queue_capacity
rejected_tasks
task_wait_duration
task_execution_duration
```

## Rate Limiter

```text
available_permissions
waiting_threads
rejected_calls
```

## TimeLimiter

```text
timeout_total
successful_total
cancelled_total
```

---

# 21. Executor observability

Esse componente é obrigatório.

Exemplo:

```text
dependency perceived duration = 800ms
```

pode esconder:

```text
Executor queue wait     500ms
Actual API call         300ms
```

Deverão existir:

```text
executor.queue.depth
executor.queue.capacity
executor.active
executor.pool.size
executor.max
executor.task.wait.duration
executor.task.execution.duration
executor.rejected
```

Timeline:

```text
Async Task
├─ queued          500ms
└─ execution       300ms
```

Sem isso, podemos culpar uma API externa por tempo gasto dentro da própria aplicação.

---

# 22. Kafka Consumer

Preservar o aprendizado do laboratório de obtenção autoritativa do lag através do broker.

Métricas:

```text
consumer lag
lag max
processing duration
records consumed
bytes consumed
poll latency
commit latency
rebalance
processing errors
consumer paused
```

Dimensões principais:

```text
cluster
topic
consumer_group
```

`partition` deverá ser opcional para dashboards operacionais devido ao aumento de cardinalidade.

Pode existir em uma visão diagnóstica detalhada.

O polling via `AdminClient` deverá ser módulo opcional.

Em grandes plataformas, a responsabilidade por métricas do broker pode ser movida para collectors/exporters centrais em vez de cada JVM consultar o cluster.

---

# 23. Kafka Producer

Não usar o termo:

```text
producer queue depth
```

como métrica genérica do Kafka broker.

Para producer observar:

```text
send rate
error rate
retry rate
request latency
ack latency
records in flight
record queue time
buffer utilization
batch size
record size
compression
```

Se a aplicação possuir:

```text
Outbox
Retry Queue
Local Buffer
```

aí sim deverão existir métricas:

```text
outbox.depth
retry_queue.depth
producer.pending
```

---

# 24. AWS SQS

Por fila:

```text
queue.depth.visible
queue.depth.inflight
queue.depth.delayed
queue.oldest_message.age

messages.sent
messages.received
messages.deleted
empty_receives

consumer.processing.duration
consumer.processing.errors
```

Separação obrigatória:

```text
queue=audit-events
queue=welcome-email
queue=payments
queue=payments-dlq
```

A descoberta automática implementada no laboratório poderá continuar disponível, mas não deverá obrigatoriamente executar `listQueues()` a cada poucos segundos em todas as aplicações de produção.

Modos:

```text
explicit queues
auto-discovery
platform-provided metrics
```

configuráveis.

---

# 25. DLQ

DLQ é uma fila com semântica operacional especial.

Deverá mostrar:

```text
depth
growth rate
oldest message age
messages in
messages out
redrive success
redrive failure
```

Um simples:

```text
DLQ > 0
```

nem sempre significa incidente grave.

Mais relevantes normalmente são:

```text
growth
age
rate
```

e contexto do domínio.

---

# 26. Database / HikariCP

Preservar integralmente o aprendizado do laboratório.

Métricas:

```text
connections.active
connections.idle
connections.pending
connections.max
connections.min
connections.timeout
connection.acquire.duration
query.duration
query.error
```

Separar:

```text
query execution
```

de:

```text
connection acquisition
```

Exemplo:

```text
DB operation = 850ms

pool wait     700ms
query         150ms
```

Isso evita diagnosticar erroneamente o banco como lento quando o problema é pool starvation.

---

# 27. Cache / Redis

Métricas:

```text
cache.get
cache.put
cache.hit
cache.miss
cache.error
cache.eviction
cache.operation.duration
```

Dashboard:

```text
Hit Ratio
Miss Ratio
P95
P99
Errors
```

Trace:

```text
Redis MISS
   |
   └── Database 190ms
```

---

# 28. Flow interruption

Preservar o recurso desenvolvido no laboratório.

Métrica lógica:

```text
flow.interruption
```

Tags:

```text
flow
failed_step
error_type_controlled
```

Trace:

```text
flow.status = INTERRUPTED
```

Logs:

```text
event=FLOW_INTERRUPTED
flow=create-payment
component=fraud-api
error_type=TimeoutException
```

Evitar utilizar mensagens de exceção como label de métrica.

---

# 29. Correlação

O starter deverá possuir dois identificadores distintos:

```text
trace_id
correlation_id
```

Eles não são conceitualmente a mesma coisa.

`trace_id` representa uma árvore/estrutura de tracing.

`correlation_id` representa uma correlação lógica definida pela aplicação/plataforma.

Não devemos depender de:

```text
correlation_id == trace_id
```

embora um trace ID possa ser utilizado como fallback quando nenhuma correlação externa foi fornecida.

HTTP:

```text
traceparent
X-Correlation-Id
```

Mensageria:

```text
trace context
correlation id
```

deverão ser propagados.

Para processamento assíncrono, OpenTelemetry recomenda propagar contexto de criação da mensagem para possibilitar correlação producer-consumer; dependendo do padrão de mensageria, `Span Links` podem representar melhor essa relação que uma árvore parent-child extremamente longa. :chatgpt-content-reference{index="5"}

---

# 30. Legs de comunicação

Preservar o conceito desenvolvido no laboratório.

Uma Leg representa uma fronteira observável:

```text
INBOUND
OUTBOUND
INTERNAL
```

Exemplo:

```text
LEG 1 INBOUND
POST /payments

LEG 2 OUTBOUND
fraud-api

LEG 3 OUTBOUND
score-api

LEG 4 OUTBOUND
Kafka payments
```

Campos:

```text
leg_number
parent_leg
type
target
phase
duration
status
trace_id
correlation_id
```

Esse conceito é especialmente útil para auditoria e reconstrução operacional do fluxo.

---

# 31. Logging estruturado

Ambientes produtivos deverão utilizar JSON estruturado.

Schema mínimo:

```json
{
  "timestamp": "...",
  "level": "INFO",
  "service": "payment-service",
  "environment": "prod",

  "trace_id": "...",
  "span_id": "...",
  "correlation_id": "...",

  "flow": "create-payment",
  "event": "LEG_RESPONSE",

  "component": "fraud-api",
  "component_type": "HTTP",

  "duration_ms": 320,
  "status": "SUCCESS"
}
```

Para erro:

```json
{
  "event": "DEPENDENCY_FAILURE",
  "component": "fraud-api",
  "error_type": "SocketTimeoutException",
  "retry_attempt": 2,
  "circuit_breaker_state": "CLOSED"
}
```

---

# 32. Não logar tudo

A implementação atual do laboratório possui valor didático ao registrar lifecycle de observações.

No starter corporativo:

```text
START operation
FINISH operation
```

para absolutamente toda Observation em `INFO` deverá estar desabilitado por padrão.

Caso contrário teremos:

```text
log amplification
I/O
storage cost
noise
```

Opções:

```text
OFF
ERROR_ONLY
IMPORTANT_EVENTS
DEBUG_ALL
```

---

# 33. Payload logging

O recurso de `@LogLeg` e SpEL masking deverá ser mantido, porém com uma alteração fundamental:

```text
includePayload = false
```

deverá ser o default corporativo.

Payload logging deverá exigir opt-in.

Também deverá possuir:

```text
field allowlist
field denylist
masking
maximum payload size
sampling
content-type policy
environment policy
```

Nunca assumir que masking automático equivale, sozinho, a conformidade LGPD, PCI-DSS ou políticas internas.

---

# 34. Dados proibidos por padrão

Não registrar automaticamente:

```text
Authorization
Cookies
Passwords
Tokens
Secrets
CPF
Credit card
Personal payload
Full SQL values
Full URL with IDs
```

Essas informações exigem política explícita.

---

# 35. Tags e cardinalidade

Métricas:

```text
LOW CARDINALITY ONLY
```

Permitidas tipicamente:

```text
service
environment
flow
component
component_type
operation
outcome
queue
topic
consumer_group
circuit_breaker
country
bounded tenant
```

Proibidas:

```text
user_id
customer_id
order_id
trace_id
correlation_id
message_id
exception.message
URL completa
CPF
```

Alta cardinalidade deverá ficar em:

```text
trace
logs
```

---

# 36. SpEL Observation Tags

Preservar:

```java
@ObservationTag(
        key = "customer_plan",
        expression = "#result?.billing()?.plan()"
)
```

e:

```java
@ObservationTag(
        key = "userId",
        expression = "#userId",
        highCardinality = true
)
```

Porém o starter deverá possuir proteção de cardinalidade.

Uma tag configurada como low-cardinality deve poder ser validada por:

```text
allowlist
maximum distinct values
known enum
policy
```

quando possível.

Falha na avaliação SpEL nunca poderá interromper negócio.

---

# 37. Custom Metrics do starter

Nomes lógicos Micrometer propostos:

```text
observability.flow.duration
observability.flow.component.attributed.duration
observability.flow.component.work.duration
observability.flow.parallel.overlap.duration
observability.flow.unattributed.duration
observability.flow.interruption
observability.flow.max.concurrency
```

No Prometheus poderão aparecer normalizados para nomes como:

```text
observability_flow_duration_seconds
observability_flow_component_attributed_duration_seconds
...
```

Não colocar `_seconds` manualmente no nome lógico do Timer se o backend Micrometer já fizer normalização de unidade.

---

# 38. Métrica para o gráfico de pizza

A pizza deverá utilizar:

```text
observability.flow.component.attributed.duration
```

Para percentual agregado por componente:

```text
rate(component_attributed_duration_sum)
/
rate(flow_duration_sum)
*
100
```

por Flow e janela.

Conceitualmente:

```promql
sum by(component) (
  rate(observability_flow_component_attributed_duration_seconds_sum{
    flow="$flow"
  }[5m])
)
/
sum(
  rate(observability_flow_duration_seconds_sum{
    flow="$flow"
  }[5m])
)
* 100
```

Isso representa:

```text
share do wall-clock agregado
```

e não:

```text
média simples das durations de uma integração
```

---

# 39. Média de contribuição por request

Se quisermos saber:

> Em média, quantos milissegundos essa integração representa de cada request?

o denominador deverá ser o número TOTAL de execuções do Flow:

```text
component attributed duration sum
/
flow execution count
```

e não apenas:

```text
component sum
/
component invocation count
```

porque uma dependência pode não participar de todas as requisições.

Exemplo:

```text
1000 requests

fraud-api chamada em 100 requests
```

Calcular média somente sobre 100 chamadas responderia outra pergunta.

---

# 40. Percentis

Toda latência importante deverá permitir:

```text
P50
P75
P90
P95
P99
MAX
```

Especialmente:

```text
Flow
HTTP dependency
Database
Message processing
Executor wait
```

Não confiar apenas em média.

Exemplo:

```text
fraud-api

AVG    120ms
P50     80ms
P95    330ms
P99   1800ms
```

A média esconderia uma degradação importante.

---

# 41. Exemplars

Preservar a evolução já realizada no laboratório.

Dashboard:

```text
P99 = 3.8s
```

deve permitir:

```text
metric exemplar
       ↓
trace
       ↓
span
       ↓
logs
```

Objetivo operacional:

```text
Aggregate → Instance → Evidence
```

---

# 42. Alerting: correção de responsabilidade

O laboratório possui:

```text
AlertDispatcher
AlertNotifier
WebhookAlertNotifier
CircuitBreakerAlertListener
SLA Guard
```

O conceito é válido, mas o starter corporativo deverá separar:

## Signal generation

Aplicação detecta:

```text
CB opened
timeout
flow interrupted
queue threshold
dependency slow
```

e publica:

```text
metric
structured event
trace event
```

## Alert decision

Normalmente responsabilidade da plataforma:

```text
Prometheus Alertmanager
Datadog Monitor
Grafana Alerting
Dynatrace
etc.
```

Não devemos fazer paging/webhook síncrono no caminho da requisição.

---

# 43. Alert notifier in-app

Caso `WebhookAlertNotifier` seja mantido:

```text
optional
disabled by default
async
bounded queue
timeout curto
circuit breaker próprio
drop policy
```

Jamais:

```text
business request
   ↓
send Slack webhook synchronously
   ↓
wait
```

Observabilidade não poderá se transformar em dependência do negócio.

---

# 44. SLO / SLI

Suporte declarativo:

```yaml
observability:
  slo:

    create-payment:
      availability: 99.9

      latency:
        p95: 800ms
        p99: 1500ms
```

Dashboard:

```text
SLI
SLO
Error Budget
Burn Rate
```

Um request individual acima de 800ms pode gerar evidência diagnóstica.

Um alerta operacional deverá preferencialmente considerar uma janela agregada.

---

# 45. Vendor neutrality

Arquitetura:

```text
Application
     |
     v
Corporate Observability Starter
     |
     +---- Micrometer Observation
     +---- Micrometer Metrics
     +---- Micrometer Tracing / OTel
     +---- Structured Logs
     |
     v
Collectors / Registries
     |
     +---- Prometheus
     +---- OTLP
     +---- Datadog
     +---- Dynatrace
     +---- New Relic
     +---- Grafana
     +---- Elastic
     +---- Loki
```

O starter não deverá ter dependência conceitual de Grafana, Prometheus ou Jaeger.

Esses produtos são implementações do laboratório.

---

# 46. Não reinventar métricas nativas

O starter deverá preservar métricas de qualidade já fornecidas por:

```text
JVM
Spring
Hikari
Resilience4j
Kafka clients
AWS SDK
HTTP clients
```

Não precisamos renomear tudo.

Criamos custom metrics somente quando existe um conceito corporativo que as bibliotecas não conhecem, por exemplo:

```text
Flow
Latency attribution
Parallel overlap
Unattributed time
Business flow status
Leg
```

---

# 47. Arquitetura modular

Estrutura recomendada:

```text
observability-platform
│
├── observability-api
│
│   ├── annotations
│   └── public contracts
│
├── observability-core
│   ├── FlowExecution
│   ├── StepExecution
│   ├── LatencyAttributionEngine
│   ├── Context model
│   └── cardinality policies
│
├── observability-spring-boot-autoconfigure
│   ├── flow
│   ├── tracing
│   ├── metrics
│   ├── logging
│   └── async
│
├── observability-resilience4j
├── observability-kafka
├── observability-aws-sqs
├── observability-http
├── observability-data
├── observability-audit
│
├── observability-spring-boot-starter
│
└── observability-test
```

Pode existir inicialmente um repositório multi-módulo único.

---

# 48. Auto Configuration

Utilizar:

```text
@AutoConfiguration
@ConditionalOnClass
@ConditionalOnBean
@ConditionalOnMissingBean
@ConditionalOnProperty
```

Exemplo conceitual:

```text
Kafka presente?
    habilita Kafka instrumentation

SQS presente?
    habilita SQS

Resilience4j presente?
    habilita resilience adapter

Feign presente?
    habilita Feign enrichment
```

Aplicação REST simples não deverá trazer Kafka e AWS SDK obrigatoriamente.

---

# 49. API pública

A API manual deve ser pequena.

## Flow

```java
@TrackFlow("create-payment")
```

## Step

```java
@TrackStep(
        value = "fraud-api",
        type = ComponentType.HTTP
)
```

## Dynamic tags

```java
@ObservationTag(...)
```

## Audit leg

```java
@LogLeg(
        target = "fraud-api",
        includePayload = false
)
```

Essas annotations descrevem semântica.

Não deverão exigir chamadas imperativas de observabilidade dentro do método.

---

# 50. Hierarquia de componentes

Padronizar:

```text
HTTP
GRPC
DATABASE
CACHE
KAFKA_PRODUCER
KAFKA_CONSUMER
SQS_PRODUCER
SQS_CONSUMER
SNS
JMS
BUSINESS
EXECUTOR
RETRY
CIRCUIT_BREAKER
BULKHEAD
RATE_LIMITER
CUSTOM
```

Isso permitirá dashboards independentes de nomes específicos das aplicações.

---

# 51. Configuração

Exemplo:

```yaml
observability:

  enabled: true

  flow:
    enabled: true

    attribution:
      enabled: true
      mode: critical-path

    unattributed:
      enabled: true

    parallelism:
      enabled: true

  tracing:
    enabled: true

  metrics:
    enabled: true

  logging:
    structured: true

    observation-lifecycle:
      level: OFF

    payload:
      enabled: false
      max-size: 8KB

  correlation:
    enabled: true
    header: X-Correlation-Id

  async:
    context-propagation: true

  resilience:
    enabled: true

  kafka:
    enabled: true

    lag:
      enabled: true
      mode: admin-client

  sqs:
    enabled: true

    discovery:
      mode: explicit

  database:
    enabled: true

  audit:
    legs:
      enabled: true
      payload: false
```

---

# 52. Observability backend failure

Regra absoluta:

```text
observability failure != business failure
```

Se:

```text
OTel Collector
Prometheus
Loki
Datadog
Jaeger
Webhook
```

estiver indisponível:

```text
business processing continues
```

Utilizar:

```text
async exporters
bounded buffers
timeouts
drop policies
non-blocking delivery
```

---

# 53. Sampling

Metrics:

```text
100%
```

porque são agregadas e baratas.

Tracing:

```text
configurável
```

Exemplo:

```text
normal traffic        sample
slow traces           retain
errors                retain
timeouts              retain
circuit open          retain
```

Tail sampling deverá preferencialmente ocorrer no collector/backend quando disponível.

Flow metrics não devem depender de trace sampling para existir.

---

# 54. Dashboards

## Dashboard A — Service Overview

```text
traffic
errors
P50
P95
P99
availability
CPU
memory
GC
threads
```

RED + JVM.

---

## Dashboard B — Flow Intelligence

Selecionar:

```text
flow=create-payment
```

Exibir:

```text
Throughput
Error rate
P50/P95/P99

Latency Composition
Work Distribution
Parallelism
Critical Path
Unattributed %
Instrumentation Coverage
```

---

## Dashboard C — Dependencies

```text
Dependency      Rate   Avg   P95   P99   Error   Timeout
---------------------------------------------------------
fraud-api       ...
customer-api    ...
database        ...
redis           ...
```

---

## Dashboard D — Resilience

```text
Circuit Breaker state
Failure rate
Slow call rate
Not permitted calls
Retries
Retry exhausted
Bulkhead utilization
Rate limiter
Timeouts
Fallbacks
```

---

## Dashboard E — Messaging

Por recurso:

```text
Kafka topic/group
SQS queue
DLQ
SNS
```

Mostrar:

```text
depth
lag
age
throughput
errors
processing latency
```

---

## Dashboard F — Runtime Saturation

```text
Hikari
Executors
Thread pools
CPU
Memory
GC
```

---

## Dashboard G — Forensic Flow

Busca:

```text
trace_id
correlation_id
flow
```

Mostrar:

```text
trace
legs
structured logs
errors
payload audit quando autorizado
```

---

# 55. Test support

Módulo:

```text
observability-test
```

Exemplos:

```java
assertThatFlow("create-payment")
    .hasComponent("fraud-api")
    .hasComponent("database")
    .hasCorrelation()
    .hasTrace();
```

E:

```java
assertThatFlow("parallel-test")
    .hasWallClockBetween(...)
    .hasWorkDurationGreaterThanWallClock()
    .hasParallelism();
```

---

# 56. Testes matemáticos obrigatórios

Latency Attribution Engine deverá possuir testes para:

```text
sequential execution
nested spans
partially overlapping spans
fully parallel spans
same start timestamp
same end timestamp
retries
backoff
timeout
cancelled future
nested async
missing instrumentation
recursive flow
```

Invariantes:

```text
attributed + unattributed ≈ wall clock

unattributed >= 0

parallel overlap >= 0

critical path <= wall clock

work may exceed wall clock
```

---

# 57. Teste obrigatório de CompletableFuture

Cenário:

```text
A = 300ms
B = 600ms
C = 400ms

executados simultaneamente
```

Esperado:

```text
Flow ≈ 600ms

Work ≈ 1300ms

Parallelism = detected

Critical component = B

Latency Composition != 1300ms
```

Este teste deverá ser obrigatório antes do starter ser considerado funcionalmente correto.

---

# 58. Testes de Resilience

Cobrir:

```text
Circuit CLOSED
Circuit OPEN
Circuit HALF_OPEN
CallNotPermitted
Retry success
Retry exhausted
Timeout
Fallback
Bulkhead rejection
```

E validar simultaneamente:

```text
metrics
trace
logs
flow status
```

---

# 59. Testes de mensageria

Kafka:

```text
producer
broker
consumer
lag
retry
consumer failure
DLQ quando aplicável
context propagation
```

SQS:

```text
send
receive
delete
visibility timeout
inflight
backlog
DLQ
correlation propagation
```

---

# 60. Testes de cardinalidade

Deverá existir teste automatizado garantindo que campos como:

```text
userId
orderId
messageId
traceId
```

não apareçam acidentalmente como labels de métricas.

Esse tipo de regressão pode gerar custos e indisponibilidade do sistema de métricas.

---

# 61. Performance budget

O starter deverá possuir benchmark.

Medir:

```text
CPU overhead
allocation rate
request latency overhead
memory
log volume
metric cardinality
```

Comparar:

```text
application without starter
vs
application with starter
```

Observabilidade deve possuir um orçamento explícito de overhead.

---

# 62. MVP técnico

Considerar MVP concluído quando uma aplicação de exemplo conseguir:

```text
REST entrypoint
   |
   +-- HTTP dependency
   |
   +-- CompletableFuture
   |      +-- dependency A
   |      +-- dependency B
   |
   +-- Redis
   |
   +-- Database
   |
   +-- Kafka
```

com:

```text
Resilience4j
Retry
Circuit Breaker
Executor
```

e produzir automaticamente:

```text
metrics
trace
logs
correlation
latency attribution
work distribution
critical path
parallel detection
unattributed time
resilience signals
queue signals
```

---

# 63. Critérios de aceitação do MVP

Para:

```text
POST /payments
```

o operador deverá conseguir enxergar:

```text
Total wall-clock          1.2s

Latency Attribution
├─ business               220ms
├─ customer               180ms
├─ verification           600ms
│   ├─ fraud              600ms CRITICAL
│   ├─ score              400ms PARALLEL
│   └─ history            250ms PARALLEL
├─ database               150ms
└─ kafka                   50ms
```

e simultaneamente:

```text
Total work                1.85s

Parallel overlap          600ms
Max concurrency           3

Circuit fraud             CLOSED
Retries                   0
Fallback                  NO
Executor wait             4ms

Unattributed              0.7%

Trace                     abc123
Correlation               xyz456

Outcome                   SUCCESS
```

---

# 64. O que o starter NÃO deverá fazer

Não deverá:

```text
instrumentar business services manualmente
duplicar spans nativos
usar traceId como metric tag
logar payload por padrão
notificar Slack síncronamente
somar spans paralelos como wall-clock
depender de Grafana
depender de Prometheus
depender de Jaeger
depender de Datadog
depender de uma única thread
criar alta cardinalidade descontrolada
falhar negócio por falha de observabilidade
```

---

# 65. Evolução do laboratório

Os componentes atuais devem ser tratados da seguinte forma:

```text
FlowContext v1
    → substituir por FlowExecution v2 concorrente

FlowTrackingAspect
    → preservar conceito, mudar motor temporal

@TrackFlow
    → preservar

@TrackStep
    → preservar e enriquecer

SpelObservationAspect
    → preservar com cardinality guard

KafkaLagMetricsBinder
    → preservar como adapter opcional

SqsMetricsBinder
    → preservar como adapter opcional

CircuitBreakerAlertListener
    → preservar como signal producer

AlertDispatcher
    → preservar, execução async/best-effort

WebhookAlertNotifier
    → opcional e disabled-by-default

LegLoggingAspect
    → preservar

SpelMaskingService
    → preservar e endurecer políticas

CorrelationContext
    → preservar conceito e separar correlationId de traceId

LoggingObservationHandler
    → reduzir verbosity em produção

Loki integration
    → transformar em backend/example, não requisito do core

Grafana dashboards
    → transformar em dashboard reference implementation

Prometheus rules
    → transformar em templates de plataforma
```

---

# 66. Decisão central

A biblioteca não deverá ser descrita simplesmente como:

> Spring Boot Starter de métricas.

Ela deverá ser concebida como:

# Flow Observability Platform SDK for Spring Boot

onde o Spring Boot Starter é o mecanismo de distribuição e auto-configuração.

O diferencial não está em criar mais timers.

Micrometer, OpenTelemetry, Resilience4j e os próprios frameworks já fazem isso muito bem.

O diferencial está em transformar esses sinais em:

```text
Flow Intelligence
```

capaz de explicar causalmente o comportamento de uma operação.

---

# 67. Perguntas que a solução deverá responder

Para qualquer Flow importante:

```text
Quanto tempo demorou?

Qual é o P50/P95/P99?

Quem consumiu esse tempo?

Qual dependência domina o critical path?

Quais operações aconteceram em paralelo?

Quanto trabalho total aconteceu?

Quanto tempo ainda não conseguimos explicar?

Houve retry?

Quanto retry custou?

Houve timeout?

O Circuit Breaker abriu?

Chamadas foram rejeitadas?

Houve fallback?

O Flow terminou degradado?

Existe saturação no executor?

Existe espera no pool de conexões?

O banco está lento ou estamos esperando conexão?

Qual fila possui backlog?

O backlog está aumentando?

Qual é a idade da mensagem mais antiga?

Qual consumer group possui lag?

O producer está falhando ou sofrendo retry?

Existe DLQ crescendo?

Consigo sair da métrica e encontrar um trace real?

Consigo sair do trace e encontrar os logs?

Consigo reconstruir todas as Legs do processamento?
```

Se essas perguntas puderem ser respondidas sem conhecimento prévio da implementação interna da aplicação, o starter cumpriu seu objetivo.

---

# 68. Decisões ainda não congeladas

Antes de declarar esta arquitetura como definitiva, deverão ser validadas experimentalmente três decisões.

## 68.1 Onde executar Latency Attribution?

Alternativa A:

```text
dentro da aplicação
```

Vantagens:

```text
métrica imediatamente disponível
backend independente
```

Desvantagens:

```text
CPU/memory overhead
estado temporário por request
complexidade concorrente dentro da JVM
```

Alternativa B:

```text
OTel Collector / trace processor
```

Vantagens:

```text
aplicação mais simples
análise baseada no trace completo
algoritmo centralizado
```

Desvantagens:

```text
dependência de infraestrutura
resultado pós-processado
complexidade do collector
```

Alternativa C:

```text
híbrida
```

Aplicação calcula métricas simples:

```text
flow duration
basic component timing
resilience
queues
```

e o backend/processor calcula:

```text
critical path
parallel attribution
advanced flow analysis
```

Esta é atualmente a alternativa mais promissora, mas deverá ser medida.

---

## 68.2 Precisão vs overhead

Precisamos medir se registrar todos os intervalos de Step em memória durante cada request possui custo aceitável.

Caso não possua:

```text
sampling do attribution engine
```

poderá ser diferente de:

```text
metrics sampling
```

---

## 68.3 Application watchdog vs platform collector

Kafka lag e SQS backlog podem ser coletados:

```text
pela aplicação
```

ou:

```text
pela plataforma
```

O laboratório demonstrou que a coleta in-app funciona.

Isso não significa automaticamente que executar milhares de `AdminClient`/AWS polling loops distribuídos entre microsserviços seja a melhor estratégia corporativa.

Essa responsabilidade deverá continuar plugável.

---

# 69. Conclusão

O laboratório já demonstrou com sucesso a maior parte dos building blocks necessários.

O passo seguinte não é simplesmente:

```text
copiar as classes para um Starter
```

O passo seguinte é transformar os aprendizados do laboratório em um contrato arquitetural mais forte.

A evolução principal será:

```text
FlowContext sequencial
          ↓
Concurrent Flow Execution Model
          ↓
Latency Attribution Engine
          ↓
Critical Path + Parallelism
          ↓
Flow Intelligence
```

mantendo todos os recursos que o laboratório já validou:

```text
AOP não intrusivo
SpEL
Metrics
Distributed Tracing
Structured Logs
Correlation
Legs
Masking
Circuit Breakers
Alert signals
Kafka Lag
SQS Depth
Hikari
Exemplars
E2E tests
Vendor neutrality
```

O objetivo final deixa de ser simplesmente observar que:

```text
POST /payments = 1.2s
```

e passa a ser explicar:

```text
POST /payments = 1.2s

Por quê?

Verification      600ms
   Fraud API      600ms ← critical path
   Score API      400ms ← parallel
   History API    250ms ← parallel

Customer API      180ms
Database          150ms
Business          220ms
Kafka              40ms

Work total        1.85s
Wall-clock        1.20s

Circuit breaker   CLOSED
Retry             0
Fallback          NO
Queue wait        4ms

Instrumentation
coverage           99.3%

Trace              abc123
Correlation        xyz456
```

Essa passa a ser a definição de sucesso da plataforma.

### O que mudou em relação à spec atual

Eu considero que essa revisão corrige **quatro riscos arquiteturais sérios** do documento do laboratório:

1. **O gráfico de pizza deixa de ser baseado em soma cega dos `@TrackStep`**. Passamos a distinguir `wall-clock`, `work` e `attributed duration`.
2. **`ThreadLocal` deixa de ser o modelo de dados** e vira apenas uma possível forma de acesso ao contexto. Isso é essencial para `CompletableFuture`, `@Async`, Reactor e executors. O próprio Micrometer oferece abstrações específicas para capturar e restaurar contexto entre threads. :chatgpt-content-reference{index="6"}
3. **A observabilidade de mensageria passa a respeitar a natureza assíncrona do sistema**. Producer e consumer não precisam necessariamente formar uma árvore gigantesca de spans; OpenTelemetry trata explicitamente propagação do contexto de criação da mensagem e links entre operações assíncronas. :chatgpt-content-reference{index="7"}
4. **Alerting e logging deixam de poder prejudicar o negócio**. O lab provou os mecanismos; o starter precisa agora colocá-los atrás de buffers, políticas, sampling e defaults seguros.

E há um detalhe que considero especialmente importante: **eu não jogaria fora nada do lab**. Ele cumpriu exatamente a função correta de um laboratório — demonstrou empiricamente quais mecanismos funcionam. A mudança agora é separar **“mecanismo validado”** de **“arquitetura generalizável”**.

Minha confiança está alta na direção geral. O único ponto em que eu ainda **não congelaria a decisão** é onde rodar o `LatencyAttributionEngine`: na JVM, no Collector/backend ou de forma híbrida. Esse é o próximo experimento que vale fazer antes de começarmos a implementar o starter definitivo.