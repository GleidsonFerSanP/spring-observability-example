# Registro de Decisões Arquiteturais (ADR - Architecture Decision Records)

Este documento registra formalmente as principais decisões arquiteturais tomadas no projeto **Spring Observability Example**, os problemas abordados, o racional técnico, os trade-offs e o resultado das implementações.

---

## 📑 Índice dos Registros

- [ADR 01: Observabilidade Não-Intrusiva e Desacoplamento via Aspectos (SpEL + FlowTracking)](#adr-01-observabilidade-não-intrusiva-e-desacoplamento-via-aspectos)
- [ADR 02: Decomposição Matemática de Latência por Entrypoint (FlowContext via ThreadLocal)](#adr-02-decomposição-matemática-de-latência-por-entrypoint)
- [ADR 03: Circuit Breaking Granular por Serviço Externo com Resilience4j](#adr-03-circuit-breaking-granular-por-serviço-externo)
- [ADR 04: Descoberta Dinâmica de Filas SQS via MeterBinder](#adr-04-descoberta-dinâmica-de-filas-sqs-via-meterbinder)
- [ADR 05: Extração Autoritativa de Lag do Kafka via AdminClient](#adr-05-extração-autoritativa-de-lag-do-kafka-via-adminclient)
- [ADR 06: Ergonomia Visual e Prevenção de Truncamento no Grafana](#adr-06-ergonomia-visual-e-prevenção-de-truncamento-no-grafana)
- [ADR 07: Engenharia de Caos e Simulação de Falhas Controladas com WireMock e Carga Contínua](#adr-07-engenharia-de-caos-e-simulação-de-falhas-controladas)
- [ADR 08: Arquitetura de Alarmística Não-Intrusiva em Duas Camadas](#adr-08-arquitetura-de-alarmística-não-intrusiva-em-duas-camadas)
- [ADR 09: Arquitetura Vendor-Neutral e Estratégia Multi-Provedor (Prometheus, Jaeger, Datadog, OTel)](#adr-09-arquitetura-vendor-neutral-e-estratégia-multi-provedor)
- [ADR 10: Rastreamento Estruturado de Pernas de Execução (Legs), Auditoria de Payloads e Mascaramento SpEL via Grafana Loki](#adr-10-rastreamento-estruturado-de-pernas-de-execução-legs-auditoria-de-payloads-e-mascaramento-spel-via-grafana-loki)
- [ADR 11: Padronização Corporativa de Logback Multi-Perfil, Appenders Assíncronos e Propagação de Correlation ID](#adr-11-padronização-corporativa-de-logback-multi-perfil-appenders-assíncronos-e-propagação-de-correlation-id)
- [ADR 12: Flow Dimensions e SPI Desacoplada de Feature Flags para Migração Operacional de Rotas](#adr-12-flow-dimensions-e-spi-desacoplada-de-feature-flags-para-migração-operacional-de-rotas)
- [ADR 13: Arquitetura Hexagonal de Observabilidade com Engine SPI e Datadog como Engine Oficial Corporativo](#adr-13-arquitetura-hexagonal-de-observabilidade-com-engine-spi-e-datadog-como-engine-oficial-corporativo)
- [ADR 14: Migração para Starter Corporativo Multi-Módulo, Política Single-Producer por Sinal e OpenTelemetry API Pura](#adr-14-migração-para-starter-corporativo-multi-módulo-política-single-producer-por-sinal-e-opentelemetry-api-pura)
- [ADR 15: Isolamento Estrito de MDC Declarativo (@MDC) e Centralização Corporativa de Logging via Logback Multi-Perfil](#adr-15-isolamento-estrito-de-mdc-declarativo-mdc-e-centralização-corporativa-de-logging-via-logback-multi-perfil)

---

## ADR 01: Observabilidade Não-Intrusiva e Desacoplamento via Aspectos

### Contexto
Em muitas aplicações corporativas, a instrumentação de métricas e rastreabilidade distribuída polui os serviços de domínio (`@Service`) com chamadas manuais como `registry.timer(...)`, `observation.lowCardinalityKeyValue(...)` ou blocos `try/finally` para cada chamada HTTP. Isso gera acoplamento com bibliotecas de infraestrutura, reduz a legibilidade e dificulta manutenções futuras.

### Decisão
1. **Interceptação nas Bordas**: As anotações de métricas são colocadas exclusivamente nos limites arquiteturais da aplicação:
   - Controladores REST (`UserOrchestratorController`)
   - Interfaces OpenFeign (`CustomerClient`, `BillingClient`, `NotificationClient`)
   - Produtores e Consumidores de Mensageria (`KafkaUserConsumer`, `SqsUserProducer`, etc.)
2. **Aspecto Genérico com Spring Expression Language (SpEL)**:
   - Criamos `@ObservationTag(key, expression)` e `SpelObservationAspect`.
   - Permite extrair dados contextuais (ex: `#userId`, `#result?.billingType()?.name()`) de forma declarativa e com avaliação segura (`SafeNavigator`).
3. O `UserOrchestratorService` mantém sua responsabilidade única focada na lógica de negócio e orquestração.

### Consequências
- **Positivas**: Separação clara de responsabilidades; regras de negócio 100% livres de código boilerplate de telemetria; facilidade para testar unitariamente os serviços sem mockar `MeterRegistry`.
- **Negativas**: AOP adiciona uma leve sobrecarga de reflexão e criação de proxies Spring, que é desprezível comparada ao tempo de I/O de rede.

---

## ADR 02: Decomposição Matemática de Latência por Entrypoint

### Contexto
O usuário requereu que o painel do Grafana apresentasse um gráfico de pizza onde a pizza inteira representasse 100% do tempo de processamento de um fluxo de entrada (entrypoint), e cada fatia representasse o tempo exato consumido por suas integrações externas, além do tempo residual de processamento interno.

Tentativas anteriores de montar esse gráfico utilizando métricas globais de `resilience4j_circuitbreaker_calls_seconds` falharam porque:
1. **Contaminação de Contexto**: Uma chamada Feign pode ocorrer tanto a partir de um endpoint REST quanto a partir de um consumidor assíncrono Kafka.
2. **Ausência de Fechamento**: A soma das chamadas Feign não totalizava 100% do tempo de resposta do endpoint, deixando um vazio inexplicado que distorcia as proporções.

### Decisão
Criar o módulo `com.gleidsonfersanp.observability.observability.flow`:
1. **`@TrackFlow`**: Marca os métodos de entrada do sistema (`GET /api/v1/orchestrator/users/{userId}` e `Kafka Consumer: user-registration-topic`).
2. **`@TrackStep`**: Marca cada integração ou subprocesso interno executado dentro do fluxo.
3. **`FlowContext` (ThreadLocal Stack)**:
   - Mantém uma pilha na thread corrente para permitir fluxos aninhados.
   - Registra o início do fluxo (`startNanos`).
   - Acumula a duração de cada `@TrackStep`.
   - No fechamento (`complete`), calcula o tempo residual:
     $$\text{internalNanos} = \max(0, \text{totalNanos} - \sum \text{stepNanos})$$
   - Publica os timers:
     - `flow_slice_duration_seconds{flow="...", step="..."}`
     - `flow_slice_duration_seconds{flow="...", step="Processamento Interno & Regras"}`
     - `flow_total_duration_seconds{flow="..."}`
4. **Grafana PromQL**:
   ```promql
   sum(rate(flow_slice_duration_seconds_sum{flow="<Nome_Do_Fluxo>"}[1m])) by (step)
   ```

### Consequências
- **Positivas**: Gráficos de pizza 100% matematicamente perfeitos; isolamento rigoroso entre requisições REST síncronas e consumidores assíncronos; cálculo exato do overhead interno.
- **Negativas**: Em fluxos reativos puros com troca constante de threads (ex: WebFlux), `ThreadLocal` exigiria propagação de contexto via Micrometer Context Propagation. Para Spring MVC clássico (thread-per-request / listener thread), a solução é robusta, limpa e de alta performance.

---

## ADR 03: Circuit Breaking Granular por Serviço Externo

### Contexto
Quando um microsserviço se comunica com múltiplos serviços terceiros (Customer, Billing, Notification), agrupar todos sob um único disjuntor (`@CircuitBreaker(name = "orchestrator")`) cria um efeito cascata: a lentidão do `customer-service` abre o disjuntor global e bloqueia desnecessariamente o faturamento ou as notificações de clientes saudáveis.

### Decisão
Configurar instâncias independentes de Circuit Breaker para cada integração externa no Resilience4j:
- `customer-service`: sliding window 10, failure rate 50%, wait duration 5s.
- `billing-service`: sliding window 10, failure rate 50%, wait duration 5s.
- `notification-service`: sliding window 10, failure rate 50%, wait duration 5s.

O fallback é configurado no nível do orquestrador apenas para recuperação graciosa quando uma das integrações falha.

### Consequências
- **Positivas**: Falhas em um serviço externo não derrubam outros fluxos; o painel do Grafana mostra exatamente qual integração está degradada; resiliência adaptativa.
- **Negativas**: Exige configuração explícita e tuning de parâmetros por integração no `application.yml`.

---

## ADR 04: Descoberta Dinâmica de Filas SQS via MeterBinder

### Contexto
Hardcodear nomes de filas SQS no código de instrumentação viola boas práticas e gera inconsistências quando novas filas são criadas em tempo de execução ou em ambientes de teste.

### Decisão
Implementar a classe `SqsMetricsBinder` como um `MeterBinder` gerenciado pelo Spring:
1. Utiliza `SqsAsyncClient` para listar as filas via `listQueues()`.
2. A cada ciclo agendado (`@Scheduled`), consulta os atributos de cada fila (`APPROXIMATE_NUMBER_OF_MESSAGES`).
3. Registra dinamicamente um `Gauge` no Micrometer sob a métrica `sqs_queue_depth` com a tag `queue="<nome-da-fila>"`.

### Consequências
- **Positivas**: Zero configuração manual no código Java para novas filas; compatibilidade total com LocalStack e AWS SQS real; exibição automática de novas filas no Grafana.
- **Negativas**: Uma chamada de rede periódica para a API do SQS (a cada 10 segundos), o que é imperceptível em ambientes controlados.

---

## ADR 05: Extração Autoritativa de Lag do Kafka via AdminClient

### Contexto
As métricas padrões expostas pelos listeners Kafka (`spring.kafka.listener`) frequentemente dependem do consumidor ter recebido commits recentes de partição ou podem omitir lag quando o consumidor está travado ou desligado.

### Decisão
Criar o `KafkaLagMetricsBinder`:
1. Utiliza o `AdminClient` do Apache Kafka para consultar diretamente os offsets do grupo de consumidores (`listConsumerGroupOffsets`).
2. Consulta o último offset das partições no broker (`listOffsets(OffsetSpec.latest())`).
3. Calcula a diferença real ($\text{Lag} = \text{Offset}_{\text{broker}} - \text{Offset}_{\text{consumer}}$) e expõe a métrica `kafka_consumer_lag_records{topic="...", group="..."}`.

### Consequências
- **Positivas**: Métrica 100% precisa e autoritativa diretamente do broker; reflete o acúmulo de mensagens mesmo que o consumidor esteja pausado ou com thread bloqueada.
- **Negativas**: Requer permissão administrativa de leitura de offsets no cluster Kafka.

---

## ADR 06: Ergonomia Visual e Prevenção de Truncamento no Grafana

### Contexto
Nas primeiras iterações do dashboard, os cards de Circuit Breaker eram muito estreitos (`w: 4`), fazendo com que o texto dos títulos e os status fossem truncados visualmente com reticências (`...`), tornando o dashboard ilegível.

### Decisão
1. **Redimensionamento dos Cards**: Os cards de Circuit Breaker foram expandidos para largura 8 (`w: 8`, `h: 4`) na grade do Grafana.
2. **Tipografia e Badges**:
   - Mapeamento explícito de valores: `1` renderiza `🟢 FECHADO` (Verde), `0` renderiza `🔴 ABERTO` (Vermelho).
   - Modo de cor em `background` com fonte grande e centralizada.
3. **Distribuição Visual em Linhas Temáticas**:
   - Linha 1: Gráficos de Pizza por Fluxo de Entrada (REST e Kafka).
   - Linha 2: Saúde dos Circuit Breakers.
   - Linha 3: Latência Ponta a Ponta e Taxa de Erros.
   - Linha 4: Mensageria (Profundidade de Fila SQS e Lag do Kafka).

### Consequências
- **Positivas**: Painel limpo, legível em telas de alta e baixa resolução; sem sobreposição de textos ou truncamentos; leitura instantânea do estado operacional da aplicação.

---

## ADR 07: Engenharia de Caos e Simulação de Falhas Controladas

### Contexto
Para validar se os alarmes, disjuntores e métricas operam corretamente sob estresse real, era necessário um gerador de carga contínua e perfis de falha reproduzíveis.

### Decisão
1. **WireMock**: Configurado na porta `8081` com endpoints mapeados para:
   - Usuários normais (`user1` a `user20`): 200 OK imediato.
   - Usuário `slow`: latência de 3.000ms para provocar timeout no Feign e abrir o disjuntor.
   - Usuário `error`: HTTP 500 para acionar o limiar de falhas de faturamento.
   - Usuário `flake`: falhas intermitentes.
2. **`massive_load.py`**:
   - Script multithreaded que gera tráfego contínuo tanto para endpoints síncronos REST quanto para publicações assíncronas no Kafka.
   - Ajustado para um ritmo estável (8 workers com intervalo de 1.5s) para evitar sobrecarga no Docker Desktop em macOS.

### Consequências
- **Positivas**: Geração contínua de métricas sem interrupção; possibilidade de demonstrar a abertura e fechamento de disjuntores ao vivo; observação do aumento e drenagem de filas e lag no Grafana.

---

## ADR 08: Arquitetura de Alarmística Não-Intrusiva em Duas Camadas

### Contexto
Sistemas distribuídos corporativos exigem detecção veloz de falhas e violações de SLA (latência alta em integrações, circuit breakers abertos, filas acumulando mensagens, exaustão de pool de banco).
Historicamente, essa necessidade leva os desenvolvedores a implementarem código condicional de disparo de alertas dentro das classes de serviço de domínio ou, no outro extremo, a dependerem apenas da raspagem lenta do Prometheus/Alertmanager (minutos para reagir).

### Decisão
Estruturar a solução de alarmística em duas camadas complementares e desacopladas:

1. **Camada 1: Alarmística Reativa In-App (Sub-segundo / Zero Invasão)**:
   - **Circuit Breakers**: `CircuitBreakerAlertListener` implementa `RegistryEventConsumer<CircuitBreaker>` do Resilience4j, capturando transições para `OPEN` ou `HALF_OPEN` em milissegundos sem tocar nos serviços ou clientes.
   - **SLA Guard de Latência**: `FlowTrackingAspect` avalia a duração medida de cada `@TrackStep` e `@TrackFlow` em relação aos thresholds configurados no `application.yml`, emitindo `INTEGRATION_LATENCY_SLA_BREACH` e `FLOW_LATENCY_SLA_BREACH`.
   - **Watchdogs de Infraestrutura**: Binders de Kafka Lag, Filas SQS e Pool HikariCP monitoram acúmulos e emitem alertas com estrangulamento de 30s.
   - **Centralizador Desacoplado**: `AlertDispatcher` distribui os alertas para múltiplos `AlertNotifier` (Logs estruturados JSON, Webhooks corporativos assíncronos) e incrementa o contador Prometheus `alerts_triggered_total`.
   - **Buffer de Consulta**: Endpoint `GET /api/v1/orchestrator/alerts` expõe os últimos 100 incidentes.

2. **Camada 2: Alarmística de Plataforma no Prometheus / Alertmanager**:
   - Criação de `prometheus-alerts.yml` com regras declarativas avaliadas continuamente para detectar degradações da frota.
   - Painéis dedicados de alertas e violações de SLA adicionados ao dashboard do Grafana.

### Consequências
- **Positivas**: Resposta imediata a incidentes na JVM; zero código de telemetria dentro das regras de negócio; arquitetura facilmente empacotável em um Starter corporativo compartilhado; correlação nativa entre métricas in-app e alertas do Prometheus.
- **Negativas**: Exige definição e calibração de limites de SLA (`application.yml`) para evitar falsos positivos em ambientes de teste.

---

## ADR 09: Arquitetura Vendor-Neutral e Estratégia Multi-Provedor

### Contexto
Organizações utilizam diferentes ecossistemas de telemetria entre ambientes:
- Em **desenvolvimento local, CI e testes**: é mandatório o uso de ferramentas gratuitas, leves e de código aberto (Prometheus, Jaeger, LocalStack, Grafana) para viabilizar testes sem custos de licença.
- Em **produção corporativa**: utilizam-se plataformas SaaS consolidadas (Datadog, Dynatrace, New Relic) ou coletores centrais (OpenTelemetry Collector).

O risco clássico é o **Vendor Lock-in**: desenvolvedores adicionam bibliotecas proprietárias (ex: `dd-trace-java`, SDKs da Datadog ou New Relic) diretamente nos serviços ou criam lógicas atreladas ao formato de métricas do Prometheus, inviabilizando a portabilidade da aplicação e do starter corporativo.

### Decisão
1. **Padrão Facade com Micrometer Observation e OpenTelemetry Standard**:
   - Todo o código de aplicação e aspectos baseia-se unicamente nas abstrações `ObservationRegistry` e `MeterRegistry`.
   - Nenhum pacote ou classe de fornecedor proprietário é importado no código Java (`com.datadoghq.*`, `io.jaegertracing.*`, etc.).
2. **Ponte de Padronização Aberta**:
   - Adotar `micrometer-tracing-bridge-otel` e `opentelemetry-exporter-otlp` como mecanismo padrão de emissão de traces e métricas.
3. **Estratégia de Ingestão por Ambiente**:
   - **Ambiente Local**: OTLP HTTP (`:4318`) enviando para o container do Jaeger; métricas expostas via endpoint `/actuator/prometheus` raspadas pelo Prometheus.
   - **Ambiente Datadog**:
     - *Opção Primária*: Envio OTLP direto para a porta `:4318` do Datadog Agent (nativo no Datadog v7.35+), sem qualquer biblioteca Datadog no Spring Boot.
     - *Opção Secundária*: Inclusão plugável de `micrometer-registry-datadog` para push direto via API da Datadog.
   - **Ambientes Dynatrace / New Relic / OTel Collector**: Mesma ponte OTLP, variando apenas o endpoint e cabeçalhos de autenticação no `application.yml`.

### Consequências
- **Positivas**:
  - 100% de reutilização de código entre ambientes locais e corporativos.
  - O starter Spring Boot pode ser adotado por qualquer time da empresa, independentemente de qual backend de APM/Métricas a diretoria escolher no futuro.
  - Conformidade estrita com o padrão global OpenTelemetry (W3C Trace Context).
- **Negativas**:
  - Requer que o Datadog Agent ou backend de destino esteja com o receptor OTLP habilitado (padrão em infraestruturas modernas de Kubernetes).

---

## ADR 10: Rastreamento Estruturado de Pernas de Execução (Legs), Auditoria de Payloads e Mascaramento SpEL via Grafana Loki

### Contexto
Em arquiteturas de microsserviços e orquestradores distribuídos, a visualização exclusiva de métricas agregadas e traces não é suficiente para certas investigações e auditorias operacionais. Engenheiros e auditores precisam:
1. Conhecer detalhadamente as etapas ou "pernas" (Legs) de comunicação (Inbound, Outbound, Internal) disparadas em cada transação.
2. Inspecionar o que foi submetido em cada chamada e qual foi a resposta exata retornada pelos serviços parceiros.
3. Garantir conformidade estrita com regulamentações como **LGPD** e **PCI-DSS**, prevenindo qualquer vazamento acidental de dados sensíveis (senhas, cartões de crédito, CPFs, e-mails) nos logs.
4. **Garantir a integridade absoluta dos dados de negócio**: mecanismos ingênuos de mascaramento que alteram diretamente os DTOs em memória corrompem o processamento subsequente da aplicação.
5. Adotar uma plataforma de centralização de logs que se integre nativamente com a pilha já adotada (Prometheus, Jaeger, Grafana).

### Decisão
1. **Abstração Declarativa de Pernas com `@LogLeg` e `@MaskField`**:
   - Criar anotações declarativas para delimitar pernas de comunicação em Controllers, Clientes HTTP e Consumers de mensageria.
   - Fornecer suporte a mascaramento granular de campos utilizando expressões **SpEL** (*Spring Expression Language*) avaliadas contra os objetos `#request`, `#args` e `#result`.
2. **Garantia de Immutabilidade via Árvores Jackson (`JsonNode`)**:
   - Os DTOs de negócio jamais são modificados em memória.
   - O mascarador serializa o objeto para uma árvore JSON desvinculada (`JsonNode`) e aplica as mutações de mascaramento estritamente sobre a árvore temporária usada pelo logger.
3. **Padrões de Máscara Pré-configurados**:
   - `EMAIL_PARTIAL` (ex.: `j***e@example.com`), `CPF_PARTIAL` (ex.: `123.***.***-00`), `CARD_PARTIAL` (ex.: `4111-11**-****-1234`), `PASSWORD` (`********`), `FULL_MASK` (`***REDACTED***`) e `CUSTOM`.
4. **Gerenciamento de Contexto Sequencial (`LegContext`)**:
   - Pilha em `ThreadLocal` para numerar sequencialmente as pernas da transação (`legNumber`, `parentLegNumber`) e computar latências individuais de cada perna.
5. **Adoção do Grafana Loki como Plataforma de Logs**:
   - Ingestão via `com.github.loki4j:loki-logback-appender` enviando lotes assíncronos diretamente da JVM para a porta `:3100` do Loki sem exigir agentes adicionais no host.
   - Indexação baseada em labels (`app`, `level`, `leg_type`, `leg_target`, `leg_phase`), reduzindo consumo de disco e CPU.
   - Correlação bidirecional com o Jaeger via Grafana *Derived Fields* (`traceId`).

### Consequências
- **Positivas**:
  - Visibilidade de ponta a ponta com auditoria completa de payloads.
  - Zero intrusividade: services e regras de negócio não possuem qualquer código de log ou máscara.
  - Segurança jurídica e técnica: dados sensíveis nunca chegam em texto claro ao Loki ou disco.
  - Integração perfeita no Grafana: navegação fluida de Métricas ➔ Logs do Loki ➔ Traces do Jaeger com um clique.
- **Negativas**:
  - Pequeno overhead de serialização JSON em métodos anotados com `includePayload = true`, devendo ser desabilitado ou reservado para fronteiras críticas em cenários de altíssimo throughput.

---

## ADR 11: Padronização Corporativa de Logback Multi-Perfil, Appenders Assíncronos e Propagação de Correlation ID

### Contexto
Em ambientes de microsserviços modernos, os requisitos operacionais de logs divergem radicalmente entre o ambiente de desenvolvimento local e a infraestrutura produtiva:
1. **Ambiente Local/Dev**: Desenvolvedores precisam de logs visualmente legíveis no terminal, com colorização ANSI (%highlight, %cyan, %clr), fácil identificação de threads, timestamps locais e exibição de correlation IDs (`[cid=...]`) e trace identifiers (`[traceId,spanId]`).
2. **Ambiente de Contêiner/Produção (Kubernetes, AWS ECS, CloudWatch, Datadog)**: Agentes de coleta e centralização de logs (Fluentbit, Vector, Promtail, CloudWatch Agent) exigem **JSON estritamente estruturado em uma linha por evento**, com campos padronizados (`timestamp`, `app`, `level`, `logger`, `thread`, `correlation_id`, `traceId`, `spanId`, `leg_*`, `message`, `exception`) e sanitização de quebras de linha para evitar o anti-padrão de stack traces fracionados em centenas de entradas no agregador.
3. **Desempenho e Não-Bloqueio**: A escrita síncrona em `System.out` / disco dentro de threads de requisição HTTP ou mensageria introduz latência de I/O crítica sob alta concorrência.
4. **Continuidade de Rastreabilidade Ponta a Ponta**: A ausência de um Correlation ID propagado unificadamente através de fronteiras HTTP (Inbound e Outbound via Feign) e filas de mensageria assíncrona (Kafka e SQS) quebra a capacidade de rastreio de solicitações que trafegam entre múltiplos sistemas.

### Decisão
1. **Separação Arquitetural Multi-Perfil no `logback-spring.xml`**:
   - Perfil `!container & !prod` (Local / Dev / Test):
     - `CONSOLE_SYNC` com destaque de cores ANSI e padrão:
       `%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(%5p) %clr(---){faint} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} [cid=%X{correlation_id:-none}] [%X{traceId:-},%X{spanId:-}] %m%n%wEx`
     - Empacotado em `ASYNC_CONSOLE` (`ch.qos.logback.classic.AsyncAppender`) com fila não descartável (`discardingThreshold=0`, `queueSize=512`).
   - Perfil `container | prod` (Produção / Cloud / Kubernetes):
     - `JSON_CONSOLE_SYNC` com `PatternLayoutEncoder` emitindo JSON estruturado mono-linha compatível com OTel, ECS e CloudWatch.
     - Sanitização de CRLF no corpo da mensagem e stack trace via `%replace(%m){'[\r\n\t]', ' '}` e `%replace(%wEx){'[\r\n\t]', ' '}`.
     - Empacotado em `ASYNC_JSON_CONSOLE` com `queueSize=1024` e `discardingThreshold=0`.
2. **Appender Nativo Grafana Loki Mantido e Padronizado**:
   - `Loki4jAppender` integrado nas raízes de logging para push direto via HTTP assíncrono para `${LOKI_URL}`, indexando labels (`app`, `level`, `leg_type`, `leg_target`, `leg_phase`) e emitindo JSON completo com correlation ID e pernas.
3. **Módulo de Correlação de Ponta a Ponta (`CorrelationContext`, `CorrelationIdFilter`, Feign e Mensageria)**:
   - `CorrelationIdFilter`: Intercepta requisições HTTP servlet com `HIGHEST_PRECEDENCE`. Resgata `X-Correlation-Id` (ou `X-Request-Id` / `correlation-id`) ou gera um UUID v4. Injeta em `MDC`, no cabeçalho de resposta HTTP e define atributos de requisição herdáveis (`RequestContextHolder.setRequestAttributes(..., true)`).
   - Integração com `ContextRegistry` do Micrometer (`ThreadLocalAccessor`) para propagação automática em pools de execução assíncrona (ex.: TimeLimiter / Circuit Breakers).
   - `FeignConfig`: Injeta interceptor de requisição que propaga o `X-Correlation-Id` em todas as chamadas HTTP downstream (`CustomerClient`, `BillingClient`, `NotificationClient`).
   - Produtores e Consumidores Kafka e SQS: Propagam e extraem o cabeçalho `X-Correlation-Id` nos envelopes de mensageria.

### Consequências
- **Positivas**:
  - Padrão corporativo unificado entre times locais e ambientes em nuvem.
  - Zero bloqueio de I/O em threads de processamento devido aos Appenders assíncronos.
  - Formato JSON impecável para ingestão direta por coletores cloud sem necessidade de regex complexos no agente.
  - Correlação imediata de qualquer falha através de `correlation_id` e `traceId` nos logs, no Grafana Loki e no Jaeger.
- **Negativas**:
  - Em cenários com volumetria extrema de logs e threads travadas, uma fila assíncrona saturada pode consumir até o limite configurado de memória antes de bloquear ou aplicar backpressure (`neverBlock=false`).

---

## ADR 12: Flow Dimensions e SPI Desacoplada de Feature Flags para Migração Operacional de Rotas

### Status
Aprovado / Implementado

### Contexto
Durante migrações operacionais de fluxos de processamento (ex: migração de rotas síncronas HTTP para rotas orientadas a cache e mensageria assíncrona), é praxe utilizar mecanismos de Feature Flags / Feature Toggles para controlar progressivamente a distribuição do tráfego.

Contudo, surgiram dois problemas fundamentais:
1. **Contaminação de Métricas Agregadas**: Se a métrica de fluxo registrar apenas `flow_total_duration_seconds{flow="..."}`, uma migração onde 50% das requisições vão pela rota legada e 50% pela nova rota resulta em distorção estatística severa. O P50, P95 e P99 tornam-se médias combinadas artificiais que impossibilitam entender a performance isolada de cada rota.
2. **Poluição de Código de Negócio**: Inserir chamadas diretas como `FlowContext.setVariant("new")` dentro de classes de serviço de negócio acopla regras de domínio com bibliotecas de infraestrutura de observabilidade, violando os princípios de separação de responsabilidades.

### Decisão
1. **Flow Dimensions de Primeira Classe (`FlowDimensions` e `FlowExecution`)**:
   - Criação de um modelo thread-safe de dimensões de baixa cardinalidade (`variant`, `feature`, `experiment`) associado ao ciclo de vida do fluxo.
   - Propagação automática de `variant` para todas as métricas de duração total (`flow_total_duration_seconds`), wall-clock (`observability.flow.duration`), trabalho de componentes (`observability.flow.component.work.duration`) e latência atribuída (`observability.flow.component.attributed.duration`).
2. **SPI Não-Intrusiva `FeatureEvaluationListener`**:
   - Criação da interface SPI `com.empresa.platform.observability.core.feature.FeatureEvaluationListener`.
   - Disponibilização do listener padrão `FlowFeatureEvaluationListener` via autoconfiguração.
   - Qualquer cliente de feature flags (local, Hazelcast, Unleash, LaunchDarkly) apenas notifica a avaliação da flag. O listener se encarrega de enriquecer o `FlowContext`, o `MDC` e a `Observation` ativa de forma completamente invisível para a aplicação.
3. **Segregação Dimensional no Tracing e Logs**:
   - O Span raiz do fluxo e os spans de passos recebem a tag de baixa cardinalidade `variant`.
   - O MDC recebe `variant`, `feature.name` e `feature.variant`, sendo limpo estritamente ao final do `@TrackFlow`.

### Consequências
- **Positivas**:
  - Comparação A/B e Canary operacional direta no Grafana e Prometheus, permitindo avaliar Throughput, SLAs e Decomposição de Latência Atribuída lado a lado.
  - Zero acoplamento ou poluição no código de serviço das aplicações.
  - Compatibilidade retroativa integral: fluxos sem flags continuam gerando métricas sem tags adicionais.
- **Negativas / Cuidados**:
  - Exige disciplina para manter variantes com baixa cardinalidade (ex: `legacy`, `new`, `v2`, `canary`), evitando utilizar valores dinâmicos ou identificadores de usuários como variantes.

---

## ADR 13: Arquitetura Hexagonal de Observabilidade com Engine SPI e Datadog como Engine Oficial Corporativo

### Status
Aprovado / Implementado

### Contexto
A organização definiu o **Datadog** como sua plataforma oficial corporativa para APM, Rastreamento Distribuído, Métricas e Dashboarding em ambientes de homologação e produção.

Uma análise profunda das capacidades nativas do Datadog revelou que uma parcela substancial dos requisitos originalmente imaginados para um "Flow Registry" customizado (descoberta de topologias, correlação de dependências e monitoramento de mensageria assíncrona) já é atendida pela plataforma:
1. **Service Map & Catalog Dinâmico**: Descoberta automática de nós de serviço, datastores, filas e dependências inferidas a partir do tráfego real de APM.
2. **Request Flow Maps com Filtros por Tags**: Capacidade do Datadog Trace Explorer de projetar grafos de execução filtrados por atributos de span arbitrários, permitindo isolar fluxos com `@flow.name` e comparar visualmente rotas migratórias via `@flow.variant` (ex: `legacy` vs `new`).
3. **Data Streams Monitoring (DSM)**: Mapeamento nativo de topologias de mensageria (Kafka e SQS), cálculo de latência de ponta a ponta (pathway latency) e detecção de lag de consumidores em tempo real, dispensando a necessidade de consultas manuais de catálogo e polling in-JVM via `AdminClient`.
4. **Dependency Map & Latency Attribution**: A métrica nativa "Avg % Exec Time" do Datadog desconta períodos de espera por spans filhos, mitigando o risco de somas duplicadas de latência.

Contudo, surgiu um risco arquitetural primordial: **Vendor Lock-in**.
Se os microsserviços ou o starter corporativo acoplarem-se a classes proprietárias do Datadog (`com.datadoghq.*`):
- O código de negócio e orquestração fica refém de uma ferramenta específica.
- O desenvolvimento local e as esteiras de CI/CD tornam-se excessivamente onerosas ou inviáveis, pois desenvolvedores necessitariam de credenciais ou instâncias simuladas do Datadog para rodar testes unitários e de integração.
- A empresa perde o poder de barganha e a liberdade de migrar para outros ecossistemas (ex: OpenTelemetry Collector, Grafana Cloud, Dynatrace).

### Decisão
1. **Arquitetura Hexagonal com Engine SPI (`ObservabilityEngine`)**:
   - Todo o código de aplicação (`@TrackFlow`, `@TrackStep`, `@ObservationTag`, `FlowContext`, `FlowDimensions`) interage exclusivamente com a fronteira agnóstica do Starter Core.
   - Criação da interface SPI `com.empresa.platform.observability.core.engine.ObservabilityEngine` definindo as operações de ciclo de vida:
     - `getCapabilities()`: expõe as capacidades suportadas pelo backend ativo (`EngineCapabilities`).
     - `startFlow(...)` e `completeFlow(...)`: gerencia o escopo do fluxo, tags semânticas e finalização.
     - `startStep(...)` e `completeStep(...)`: gerencia spans de subprocessos e atribuição temporal.
     - `recordFlowInterruption(...)` e `recordStepInterruption(...)`: registra falhas e degradações.
     - `tagAttribute(key, value)`: enriquece o contexto ativo sem acoplamento a modelos proprietários.
2. **Datadog como Engine Oficial Corporativa (`DatadogObservabilityEngine`)**:
   - Desenvolvida como o adaptador padrão para ambientes corporativos e de produção.
   - **Zero Dependências Fechadas**: Opera em conjunto com o `dd-java-agent` através da ponte aberta OpenTelemetry (`io.opentelemetry:opentelemetry-api` via `DD_TRACE_OTEL_ENABLED=true`) e Micrometer Observation.
   - Aplica a convenção semântica de atributos Datadog:
     - Spans: `flow.name`, `flow.variant`, `flow.step`, `flow.type`, `flow.status`, `feature.name`, `feature.variant`.
     - Erros: `error.type`, `error.message`.
   - **Economia de Recursos com DSM**: Reporta `requiresInJvmLagPolling() == false`. Quando executando sob o Datadog, o starter suprime o polling periódico de lag via `KafkaLagMetricsBinder` e `SqsMetricsBinder`, delegando essa telemetria ao Data Streams Monitoring nativo do Datadog Agent.
3. **Micrometer/OTel como Engine de Referência e Portabilidade (`MicrometerObservabilityEngine`)**:
   - Adaptador padrão ativado em ambientes locais (`dev`), pipelines de CI/CD e testes automatizados.
   - Opera via `MeterRegistry` (Prometheus) e `ObservationRegistry` (Jaeger/OTLP), garantindo que todo o ecossistema local do Docker Compose (Prometheus, Grafana, Jaeger, Loki) permaneça 100% funcional sem qualquer licença externa.
4. **Configuração Declarativa e Seleção por Perfil**:
   - Controlada pela propriedade `observability.engine=datadog|micrometer|opentelemetry`.
   - Padrão em produção/nuvem: `datadog`.
   - Padrão em testes e local: `micrometer`.

### Consequências
- **Positivas**:
  - **Zero Vendor Lock-in**: Código de domínio e orquestração 100% puro e agnóstico de fornecedor.
  - **Aproveitamento Máximo do Datadog**: Request Flow Maps, DSM e Service Maps operam com dados de alta fidelidade sem exigir código Datadog na aplicação.
  - **Custo e Autonomia Local**: Desenvolvedores e testes executam localmente com ferramentas open-source leves sem pagar licenças nem depender de internet.
  - **Eficiência de Rede e CPU**: Eliminação de polling redundante de lag no Kafka e SQS em produção corporativa.
- **Negativas / Cuidados**:
  - Requer que o ambiente produtivo garanta a presença do `dd-java-agent` na inicialização da JVM com a variável `DD_TRACE_OTEL_ENABLED=true`.
  - Exige manutenção contínua da conformidade semântica de tags entre os adaptadores.

---

## ADR 14: Migração para Starter Corporativo Multi-Módulo, Política Single-Producer por Sinal e OpenTelemetry API Pura

### Status
Aprovado / Implementado

### Contexto
A evolução do projeto de um laboratório de microsserviços (`user-orchestrator`) para um Spring Boot Starter corporativo reutilizável exigiu o enfrentamento de quatro desafios fundamentais de arquitetura de telemetria em escala de produção:

1. **Conflito de Tracing Engines na JVM**: A coexistência na mesma JVM de um agente Java de tracing (como `dd-java-agent`) com bridges internas do Micrometer (`micrometer-tracing-bridge-otel`) e SDKs embutidos (`opentelemetry-sdk`, exportadores OTLP) gera concorrência destrutiva:
   - Spans duplicados ou desconexos, quebrando o trace context distribuído entre camadas síncronas e assíncronas.
   - Alto consumo de CPU e contenção de threads devido a múltiplos interceptadores atuando sobre as mesmas chamadas de rede e métodos instrumentados.
   - Datadog e OpenTelemetry recomendam explicitamente que, quando o agente da plataforma estiver presente, a aplicação deve interagir unicamente com a **OpenTelemetry API**, deixando o ciclo de vida do Tracer e o exportador sob responsabilidade exclusiva do agente da JVM.

2. **Risco de Duplicidade de Telemetria e Faturas Abusivas em SaaS**:
   - Sem uma governança rígida, aplicações em migração frequentemente exportam o mesmo conjunto de métricas para múltiplos destinos (ex: Prometheus local e Datadog via Datadog MeterRegistry).
   - Isso resulta em faturamento duplicado ou triplicado de ingestão de métricas em serviços SaaS, além de sobrecarga na rede interna e na JVM.

3. **Falácia da Adição Linear em Fluxos Concorrentes**:
   - Em operações que executam chamadas concorrentes via `CompletableFuture`, threads paralelas ou reativas, o cálculo sequencial tradicional de latência interna ($T_{internal} = T_{total} - \sum T_{steps}$) falha gravemente, produzindo valores negativos ou somatórios de trabalho que excedem 100% da duração física (*wall-clock*).

4. **Acoplamento de Domínio a Frameworks de Observabilidade**:
   - Modelos de negócio e bibliotecas internas de domínio não devem ser obrigados a depender de Spring Boot, Micrometer ou SDKs pesados de telemetria apenas para declarar intenções semânticas de observabilidade (`@TrackFlow`, `@TrackStep`, `@FlowDimension`).

### Decisão

1. **Arquitetura Multi-Módulo Segregada em Camadas**:
   - **`observability-api`**: Módulo leve de dependência zero (POJO / Java puro). Contém anotações de domínio (`@TrackFlow`, `@TrackStep`, `@FlowDimension`, `@ObservationTag`, `@LogLeg`) e interfaces de dimensão. Pode ser incluído em qualquer módulo de negócio sem acoplamento a Spring, Micrometer ou OTel.
   - **`observability-core`**: Núcleo agnóstico de framework. Contém a modelagem temporal de fluxos (`FlowExecution`, `StepExecution`), o `LatencyAttributionEngine` com algoritmo de união de intervalos, o detector de runtime de tracing (`TracingRuntimeDetector`), a política de governança de cardinalidade (`CardinalityPolicy`) e o motor de auditoria/mascaramento de dados (`SpelMaskingService`).
   - **`observability-autoconfigure`**: Autoconfiguração inteligente do Spring Boot (`AutoConfiguration.imports`, `@ConditionalOnClass`, `@ConditionalOnProperty`). Orquestra os aspectos AOP, filtros Servlet de correlação, decoradores assíncronos e instrumentações automáticas para Feign, Kafka, SQS, HikariCP e Resilience4j, além do validador de topologia (`ObservabilityTopologyValidator`).
   - **`observability-spring-boot-starter`**: Starter corporativo umbrella. Agrega as dependências necessárias (`api`, `core`, `autoconfigure` e `micrometer-core`) para uso *plug-and-play* nas aplicações consumidoras.
   - **`observability-test`**: Módulo de testes isolado contendo utilitários baseados em `ApplicationContextRunner` e asserções customizadas de topologia para validar configurações sem poluir artefatos produtivos.
   - **`observability-legacy-compat`**: Camada de adaptadores para manter compatibilidade binária e de configuração com versões prévias do laboratório.
   - **`observability-demo`**: A aplicação de demonstração completa (`user-orchestrator`), validando a integração de todas as capacidades sob tráfego real, testes de integração e cenários de caos.

2. **Adoção Exclusiva da OpenTelemetry API Pura (Zero SDK/Exporters no Starter)**:
   - O starter base declara dependência estritamente com `io.opentelemetry:opentelemetry-api`.
   - Nenhuma dependência com `opentelemetry-sdk`, `opentelemetry-exporter-*` ou `micrometer-tracing-bridge-otel` é empacotada no starter corporativo.
   - Em produção corporativa sob Datadog, o `-javaagent:dd-java-agent.jar` (com `DD_TRACE_OTEL_ENABLED=true`) injeta automaticamente a implementação oficial do Tracer OpenTelemetry. A aplicação manipula spans e atributos diretamente via API padrão aberta sem conflitos de engine.

3. **Política "Single-Producer Per Signal" e Seleção por Perfis**:
   - Estabelecida a regra de ouro: **cada sinal de observabilidade (Métricas, Traces e Logs) deve possuir exatamente UM produtor ativo na JVM**.
   - **Perfil Padrão Corporativo**: `observability.profile: datadog`. Ativa o registro de métricas voltado para o ecossistema Datadog.
   - **Perfil Alternativo**: `observability.profile: prometheus`. Habilita o `PrometheusMeterRegistry` e o endpoint `/actuator/prometheus` para ambientes locais, CI/CD ou clusters baseados em Prometheus/Grafana.
   - **Guarda contra Exportação Duplicada (Dual-Export Guard)**: O validador de topologia (`ObservabilityTopologyValidator`) analisa os registries de métricas ativos. Se múltiplos exporters forem detectados sem que a flag explícita `observability.metrics.allow-dual-export: true` tenha sido configurada, a aplicação aborta a inicialização (*fail-fast*) com instruções acionáveis de resolução, prevenindo surpresas na fatura da nuvem.
   - **Detecção de Agentes Concorrentes**: O `TracingRuntimeDetector` inspeciona os argumentos da JVM (`-javaagent`) via JMX `RuntimeMXBean` e alerta/bloqueia a inicialização caso múltiplos agentes concorrentes (ex: Datadog Agent + OpenTelemetry Java Agent) estejam injetados simultaneamente.

4. **Motor de Atribuição de Latência com União Geométrica de Intervalos**:
   - O `LatencyAttributionEngine` modela a execução de cada passo como um intervalo temporal $[start_i, end_i]$.
   - Em fluxos com execução paralela ou concorrente, calcula-se a união dos intervalos ativos $\bigcup I_i$.
   - O tempo de processamento interno real é dado por:
     $$T_{internal} = T_{wall\_clock} - \mu\left(\bigcup_{i=1}^{n} I_i\right)$$
   - Essa formulação garante as invariantes matemáticas de que $0 \le T_{internal} \le T_{wall\_clock}$, eliminando frações negativas e preservando a fidelidade da decomposição de latência mesmo sob paralelismo massivo.

5. **Governança Estrita de Cardinalidade (`CardinalityPolicy`)**:
   - Dimensões de métricas (Time-Series DB) são restritas a valores previsíveis e de baixa cardinalidade (`variant`, `feature`, `flow.name`, `step.name`, `status`).
   - Identificadores de entidades de alta cardinalidade avaliados dinamicamente via expressões SpEL (`#userId`, `#orderId`, tokens) são direcionados estritamente aos atributos dos Spans de rastreamento ou ao MDC de logs, impedindo a ocorrência de "cardinality bombs" nos backends de métricas.

### Consequências

- **Positivas**:
  - **Zero Conflitos de Tracing na JVM**: O Datadog Java Agent atua como provedor canônico sem concorrência de SDKs locais, garantindo traces distribuídos contínuos e sem quebras de contexto.
  - **Prevenção Centralizada de Custos**: Bloqueio ativo contra duplicidade de envio de métricas para múltiplos provedores pagos.
  - **Desacoplamento Arquitetural**: Módulos de negócio importam apenas `observability-api` (poucos kilobytes, zero dependências transitivas), mantendo arquiteturas limpas e hexagonais.
  - **Exatidão Matemática de Telemetria**: Eliminação de distorções em métricas de latência causadas por chamadas paralelas em microserviços.
  - **Flexibilidade Operacional**: Alternância limpa e declarativa entre perfis `datadog` e `prometheus` via configuração Spring Boot (`application.yml`).

- **Negativas / Cuidados**:
  - Em ambientes locais onde o desenvolvedor deseja visualizar traces sem o `dd-java-agent`, é necessário configurar um exportador ou agente apropriado via perfil de desenvolvimento.
---

## ADR 15: Isolamento Estrito de MDC Declarativo (`@MDC`) e Centralização Corporativa de Logging via Logback Multi-Perfil

### Contexto
Historicamente, enriquecer logs estruturados com identificadores de negócio (ex: `userId`, `tenantId`, `channel`, `flowType`) dependia de chamadas manuais a `org.slf4j.MDC.put(k, v)` e `org.slf4j.MDC.remove(k)` espalhadas por controllers e services. Essa abordagem causava três problemas graves:
1. **Poluição de Código de Domínio**: Métodos de negócio eram forçados a importar classes de infraestrutura de logging do SLF4J e envolver suas execuções em blocos `try/finally` para evitar vazamentos de dados.
2. **Vazamento Crítico em Thread Pools (Thread-Local Leakage)**: Em servidores de aplicação com reuso de threads (como Tomcat Workers, Netty EventLoops e executors `@Async`), esquecer de invocar `MDC.remove()` ou a ocorrência de exceções antes do fechamento do bloco deixava a thread "suja" com identificadores de requisições anteriores. Um cliente subsequente processado naquela mesma thread acabava tendo seus logs marcados com o `userId` de outro usuário, gerando graves violações de privacidade e incidentes de segurança.
3. **Confusão Arquitetural entre Logs e Métricas**: Tentativas anteriores de reutilizar anotações de métricas para popular o MDC (ou vice-versa) esbarravam no problema de cardinalidade: atributos de log são de alta cardinalidade por natureza (`userId`, `cpf`), enquanto métricas TSDB (Prometheus/Datadog) exigem cardinalidade finita e baixa (`plan`, `region`). Misturar ambos em uma única anotação colocava em risco a estabilidade do banco de métricas.
4. **Duplicação de Arquivos XML de Configuração**: Cada microsserviço replicava dezenas de linhas de `logback-spring.xml` com appenders complexos e padrões de conversão divergentes, dificultando a ingestão uniforme no Datadog Logs ou Grafana Loki.

### Decisão
1. **Criação da Anotação Declarativa `@MDC` e `@MDCs`**:
   - Criada no módulo puro `observability-api`, totalmente desacoplada de Spring e Micrometer.
   - Suporta extração direta de argumentos anotados em métodos (`@PathVariable @MDC("userId") String userId`).
   - Suporta avaliação dinâmica de expressões SpEL em objetos de requisição (`@MDC(key = "userId", expression = "#request.userId")`).
   - Suporta declaração de valores estáticos de contexto (`@MDC(key = "channel", value = "web")`).
   - Agrupamento em lote via `@MDCs({ ... })`.
2. **Semântica Estrita de Pilha (Stack Semantics) no `MdcAspect`**:
   - O aspecto intercepta o método alvo (`@Around`) e captura o estado anterior da chave na thread.
   - Empilha o valor pré-existente antes de aplicar o novo valor.
   - Em um bloco `finally` garantido, restaura o valor anterior (se existia) ou executa `MDC.remove()`.
   - Compatível com proxies CGLIB e anotações herdadas através de `AopUtils.getMostSpecificMethod`.
3. **Separação Cristalina entre `@MDC` e `@ObservationTag`**:
   - `@MDC`: Exclusivo para o contexto textual da thread (SLF4J MDC), alimentando logs no console, Loki e Datadog Logs. Alta cardinalidade permitida livremente.
   - `@ObservationTag`: Exclusivo para Micrometer Observation, com governança explícita via `lowCardinality=true` (TSDB) e `lowCardinality=false` (Spans de tracing).
4. **Centralização Corporativa de Logging com Injeção via `logback.yml`**:
   - O starter empacota `logback.yml` contendo os defaults corporativos (formatos de console ANSI colorido com `cid`, `traceId`, `spanId`, `flow` e `step`).
   - Injetado na inicialização do Spring Boot via `ObservabilityLoggingEnvironmentPostProcessor` com menor precedência, permitindo override limpo no `application.yml` dos microsserviços.
   - Fornece o arquivo `observability-logback-defaults.xml` para inclusão modular via `<include resource="..."/>` em microsserviços com `logback-spring.xml` próprio.

### Consequências
- **Positivas**:
  - Eliminação de 100% dos `MDC.put` / `MDC.remove` manuais do código da aplicação.
  - Risco zero de vazamento de contexto entre threads ou mistura de identificadores de clientes em pools reusados.
  - Separação completa de preocupações: logs textuais vs métricas dimensionais.
  - Padronização visual em desenvolvimento local (ANSI) e estruturada em produção (JSON monolinha).
  - Validação automatizada na suíte de testes (`MdcEnrichmentIntegrationTest`).
- **Negativas / Cuidados**:
  - Avaliação de expressões SpEL muito complexas pode adicionar microssegundos adicionais por chamada (mitigado pelo cache de expressões compiladas no Spring ExpressionParser).

