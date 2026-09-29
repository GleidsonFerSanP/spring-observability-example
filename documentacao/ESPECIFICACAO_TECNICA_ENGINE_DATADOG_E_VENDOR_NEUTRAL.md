# Especificação Técnica: Arquitetura Hexagonal de Observabilidade, Engine SPI e Datadog como Engine Oficial Corporativo

## Documento Normativo e de Engenharia de Plataforma
**Status**: Aprovado / Em Implementação  
**Versão**: 1.0.0  
**Data**: 2026-09-29  
**Referência ADR**: [ADR 09: Arquitetura Vendor-Neutral](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/DECISOES_ARQUITETURAIS_ADR.md#adr-09-arquitetura-vendor-neutral-e-estratégia-multi-provedor), [ADR 12: Flow Dimensions](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/DECISOES_ARQUITETURAIS_ADR.md#adr-12-flow-dimensions-e-spi-desacoplada-de-feature-flags-para-migração-operacional-de-rotas) e [ADR 13: Engine SPI & Datadog Engine](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/DECISOES_ARQUITETURAIS_ADR.md#adr-13-arquitetura-hexagonal-de-observabilidade-com-engine-spi-e-datadog-como-engine-oficial-corporativo)

---

## 🎯 1. Visão Executiva e Posicionamento Estratégico

A governança corporativa de engenharia definiu o **Datadog** como a **ferramenta oficial e autoritativa de observabilidade, APM, rastreamento distribuído e monitoramento de produção** da organização.

Diante dessa definição e da análise das capacidades avançadas do Datadog, estabeleceu-se uma premissa fundamental:

> **Não reinventar a roda construindo um "Flow Registry" customizado (banco de dados, serviços de topologia ou catálogos manuais). O Datadog já fornece nativamente entre 80% e 90% dos recursos necessários via APM, Request Flow Maps, Service Map, Data Streams Monitoring e Catalog.**

Contudo, para blindar o ecossistema corporativo contra os riscos de **Vendor Lock-in** e garantir que desenvolvedores mantenham velocidade e autonomia executando testes unitários, testes de integração e desenvolvimento local com a pilha open-source (Prometheus, Jaeger, Grafana, Loki), adotamos uma **Arquitetura Hexagonal de Observabilidade (Ports and Adapters)** orientada por uma **Engine SPI**.

```text
               ┌────────────────────────────────────────────────────────┐
               │         APLICAÇÃO & REGRAS DE NEGÓCIO (100% PURAS)       │
               │   @TrackFlow   @TrackStep   @ObservationTag   @LogLeg  │
               └──────────────────────────┬─────────────────────────────┘
                                          │
                                          ▼
               ┌────────────────────────────────────────────────────────┐
               │          CORE DA PLATAFORMA (PORTA DE ENTRADA)         │
               │ FlowContext  FlowDimensions  LatencyAttributionEngine  │
               │           SPI: ObservabilityEngine Port                │
               └──────────────┬──────────────────────────┬──────────────┘
                              │                          │
              ┌───────────────┴───────────────┐          │
              │                               │          │
              ▼                               ▼          ▼
   ┌──────────────────────┐        ┌──────────────────────┐
   │    ADAPTADOR OFICIAL │        │ ADAPTADOR REFERÊNCIA │
   │ Datadog Observability│        │      Micrometer /    │
   │        Engine        │        │   OpenTelemetry      │
   └──────────┬───────────┘        └──────────┬───────────┘
              │                               │
              ▼                               ▼
    Produção / Staging                 Local Dev / CI / Testes
    - Datadog APM                      - Prometheus (:9090)
    - Request Flow Map                 - Jaeger OTLP (:4318)
    - Data Streams (DSM)               - Grafana Dashboards
    - Datadog Catalog                  - Grafana Loki (:3100)
    - Zero Libs Datadog na JVM         - Zero Custos de Licença
```

---

## 🔍 2. Análise Detalhada: Capacidades Nativas Datadog vs Engine Customizada

A tabela a seguir consolida a avaliação comparativa entre construir mecanismos manuais de plataforma versus alavancar recursos nativos do Datadog:

| Requisito de Negócio / Flow | Abordagem Própria / Customizada | Recurso Nativo do Datadog | Decisão Arquitetural |
| :--- | :--- | :--- | :--- |
| **Descoberta de Topologia** | `FlowRegistryService` com banco relacional registrando nós e arestas. | **Service Map & Catalog**: O Datadog APM infere nós, endpoints, bancos e filas automaticamente do tráfego real. | **Usar Datadog**: Elimina necessidade de registrar ou manter topologias manuais. |
| **Visualização de Fluxo Específico** | UI de grafo própria renderizando diagrama de blocos de um Flow. | **Request Flow Map**: Permite filtrar traces por tags de span (ex: `@flow.name`) e renderiza o grafo do fluxo. | **Usar Datadog**: Request Flow Map já plota latências e taxas de erro por nó. |
| **Comparação de Migração de Rota (A/B / Feature Flag)** | Dashboards manuais combinando séries temporais agregadas. | **Request Flow Map com tag `@flow.variant`**: O Datadog permite comparar grafos e distribuições de rotas. | **Usar Datadog**: Rota legada e nova rota isoladas por `@flow.variant:legacy` e `@flow.variant:new`. |
| **Mapeamento Assíncrono (Kafka & SQS)** | Polling periódico in-JVM (`AdminClient`, `GetQueueAttributes`) + emissores de gauge. | **Data Streams Monitoring (DSM)**: Rastreia latência end-to-end de mensagens (pathway latency) e lag. | **Usar Datadog DSM**: Desativar polling in-JVM em produção corporativa. |
| **Distribuição de Latência (P50/P95/P99)** | Geração manual de timers do Micrometer com alta cardinalidade. | **Trace Explorer com Group By (até 4 dimensões)**: Calcula distribuições de qualquer span tag sem custos de série temporal. | **Híbrido**: Tags semânticas em spans para análise exploratória + timers de baixa cardinalidade para alertas. |
| **Decomposição de Latência (Attribution)** | `LatencyAttributionEngine` calculando residual e concorrência na JVM. | **Dependency Map (Avg % Exec Time)**: Desconta tempo de espera por spans filhos na Resource Page. | **Híbrido**: `LatencyAttributionEngine` gera métricas padronizadas para dashboards locais e Datadog Metrics; Dependency Map apoia investigação em spans. |

---

## 🗺️ 3. O Request Flow Map do Datadog para Flow Dimensions e Feature Flags

O Datadog documenta oficialmente que o **Request Flow Map** no Trace Explorer pode ser filtrado por qualquer combinação de tags de spans customizadas, citando explicitamente **feature flags** e **shadow deployments**.

### Como a Engine Datadog viabiliza isso:
Quando um fluxo `@TrackFlow(value = "UserOrchestrationFlow")` é executado com uma feature flag de rota avaliada (`variant = "new"`), o `DatadogObservabilityEngine` injeta nos spans:
- `flow.name`: `UserOrchestrationFlow`
- `flow.variant`: `new`
- `flow.step`: nome do passo atual (ex: `FetchCustomerCache`, `PublishKafkaUserCreated`)
- `feature.name`: `feature.user.v2-async-route`
- `feature.variant`: `new`

### Consulta no Datadog Trace Explorer:
```text
@flow.name:UserOrchestrationFlow @flow.variant:new
```

### Projeção Visual Gerada Automaticamente pelo Datadog:
```text
                    [ Rota Nova: @flow.variant:new ]

                         user-orchestrator
                               │
                ┌──────────────┴──────────────┐
                │                             │
                ▼                             ▼
       [Redis: CustomerCache]         [BillingClient HTTP]
         P95: 1.2ms (99.9%)             P95: 180ms (99.5%)
                │
                ▼
       [Kafka: users.created]
         P95: 4.5ms (100%)
```

Enquanto a consulta da rota legada:
```text
@flow.name:UserOrchestrationFlow @flow.variant:legacy
```
projeta:
```text
                   [ Rota Legada: @flow.variant:legacy ]

                         user-orchestrator
                               │
                ┌──────────────┴──────────────┐
                │                             │
                ▼                             ▼
      [CustomerClient HTTP]           [BillingClient HTTP]
       P95: 220ms (98.9%)              P95: 180ms (99.5%)
                │
                ▼
     [NotificationClient HTTP]
       P95: 410ms (97.8%)
```

Sem criar nenhum dashboard manual, os times de engenharia obtêm visualização topológica comparativa imediata!

---

## ⚡ 4. Datadog Data Streams Monitoring (DSM) para Kafka e SQS

O **Data Streams Monitoring** do Datadog é a solução corporativa para observabilidade de pipelines de mensageria assíncrona. Ele injeta metadados em cabeçalhos de mensagens e calcula:
- **Pathway Latency**: Tempo total desde a publicação original pelo produtor até o consumo final pelo último consumidor do pipeline.
- **Consumer Lag**: Atraso de consumo relativo à taxa de ingestão do cluster Kafka ou fila SQS.
- **Queue Backlog**: Volume de mensagens aguardando processamento.

### ⚠️ Cuidados Técnicos e Restrições Críticas Documentadas:

1. **Restrição de Atributos do AWS SQS (Limite de 10 Cabeçalhos)**:
   - O Amazon SQS impõe um limite estrito de **no máximo 10 atributos de mensagem (`MessageAttributeValue`)** por envelope.
   - O Datadog DSM consome **1 atributo** para injetar o contexto de rastreamento e pathway (`_datadog`).
   - **Regra de Engenharia**: Aplicações produtoras que enviarem mensagens para SQS sob Datadog DSM **devem utilizar no máximo 9 atributos customizados de negócio**. Ultrapassar esse limite faz com que o SDK da AWS lance exceção de validação `Number of message attributes [11] exceeds the allowed maximum [10]`.

2. **Versão Mínima do Cliente Kafka para Lag Nativo**:
   - O cálculo de lag nativo pelo DSM sem dependência de polling externo requer `kafka-clients >= 3.8` (ou que o Datadog Agent monitore os offsets de consumer groups no cluster).
   - O Spring Boot 3.2 gerencia `kafka-clients:3.6.1`. Portanto, a configuração de tópicos deve garantir visibilidade de grupos no cluster via Datadog Kafka Integration.

3. **Supressão de Polling in-JVM e Economia de Recursos**:
   - No adaptador de referência (`MicrometerObservabilityEngine`), o starter utiliza os binders `KafkaLagMetricsBinder` e `SqsMetricsBinder` para consultar periodicamente o Kafka via `AdminClient` e o SQS via `GetQueueAttributes`.
   - Quando o `DatadogObservabilityEngine` está ativo, `capabilities.requiresInJvmLagPolling()` retorna **`false`**. O starter **desativa o agendamento de polling in-JVM**, eliminando chamadas repetitivas de rede para a AWS e para o broker Kafka, delegando o monitoramento de lag integralmente ao Datadog Agent.

---

## 🏗️ 5. Arquitetura Hexagonal: A Engine SPI

A interface SPI `ObservabilityEngine` estabelece a fronteira entre as regras de observabilidade de negócio e os provedores de telemetria.

### Estrutura de Pacotes:
```text
com.empresa.platform.observability.core.engine
├── ObservabilityEngine.java          <- Interface SPI principal
├── EngineCapabilities.java           <- Descoberta dinâmica de recursos do engine
├── ObservabilityCapability.java      <- Enum de capacidades formais
├── FlowScope.java                    <- Controle de escopo de fluxo (AutoCloseable)
├── StepScope.java                    <- Controle de escopo de subprocesso
├── MicrometerObservabilityEngine.java<- Implementação padrão (Prometheus/Jaeger/Loki)
└── DatadogObservabilityEngine.java   <- Implementação oficial corporativa (Datadog APM/DSM)
```

### Contrato da SPI (`ObservabilityEngine.java`):
```java
package com.empresa.platform.observability.core.engine;

import com.empresa.platform.observability.core.flow.FlowDimensions;
import com.empresa.platform.observability.core.flow.FlowExecution;

public interface ObservabilityEngine {

    EngineCapabilities getCapabilities();

    FlowScope startFlow(String flowName, String flowType, FlowDimensions dimensions);

    void completeFlow(FlowExecution execution, FlowScope scope);

    void recordFlowInterruption(String flowName, String stepName, Throwable error, FlowDimensions dimensions, FlowScope scope);

    StepScope startStep(String flowName, String stepName, String stepType, FlowDimensions dimensions);

    void completeStep(String flowName, String stepName, String stepType, long durationNanos, FlowDimensions dimensions, StepScope scope);

    void recordStepInterruption(String flowName, String stepName, Throwable error, FlowDimensions dimensions, StepScope scope);

    void tagAttribute(String key, String value);
}
```

### Capacidades da Engine (`EngineCapabilities.java`):
```java
public class EngineCapabilities {
    private final String engineName;
    private final boolean supportsServiceMap;
    private final boolean supportsRequestFlowMap;
    private final boolean supportsDataStreamsMonitoring;
    private final boolean supportsNativeLatencyAttribution;
    private final boolean requiresInJvmLagPolling;

    public static EngineCapabilities datadog() {
        return new EngineCapabilities("datadog", true, true, true, true, false);
    }

    public static EngineCapabilities micrometer() {
        return new EngineCapabilities("micrometer", false, false, false, false, true);
    }
}
```

---

## 🔌 6. Implementação dos Adaptadores

### 6.1 Adaptador Datadog (`DatadogObservabilityEngine`)
- **Estratégia Zero Jars Fechados**: Não importa classes do SDK fechado da Datadog (`dd-trace-api`).
- **Ponte com OpenTelemetry API**: Interage com `io.opentelemetry.api.trace.Span.current()`. Quando a aplicação roda com `-javaagent:dd-java-agent.jar` e `DD_TRACE_OTEL_ENABLED=true`, as chamadas de `setAttribute(...)` são diretamente interceptadas pelo Datadog Agent, alimentando o APM e o Request Flow Map.
- **Convenção Semântica Datadog**:
  - Tags de Spans: `flow.name`, `flow.variant`, `flow.step`, `flow.status`, `feature.name`, `feature.variant`.
  - Tags de Erro: `error.type`, `error.message`.
- **Métricas no DogStatsD / Datadog MeterRegistry**:
  - `flow_total_duration_seconds{flow, variant, ...}`
  - `observability.flow.duration{flow, variant, status}`
  - `observability.flow.component.attributed.duration{flow, variant, component}`

### 6.2 Adaptador Micrometer / Referência (`MicrometerObservabilityEngine`)
- Utiliza `io.micrometer.observation.ObservationRegistry` e `io.micrometer.core.instrument.MeterRegistry`.
- Emite tags de baixa cardinalidade (`flow`, `step`, `variant`, `step.type`).
- Garante total compatibilidade com os painéis Grafana existentes (`CATALOGO_DE_METRICAS_E_QUERIES_DASHBOARD.md`) e com o Jaeger OTLP.

---

## ⚙️ 7. Configuração Operacional e Execução em Produção

### 7.1 Seleção de Engine no `application.yml`:

```yaml
# Padrão para Ambientes de Produção / Cloud (Kubernetes / AWS ECS)
observability:
  enabled: true
  engine: datadog # datadog | micrometer | opentelemetry
  flow-tracking:
    enabled: true
  alerting:
    enabled: true

---
# Perfil Local / Testes Automatizados (application-test.yml ou application-local.yml)
observability:
  engine: micrometer
```

### 7.2 Parâmetros de Inicialização da JVM em Produção com Datadog:

```bash
java \
  -javaagent:/opt/datadog/dd-java-agent.jar \
  -Ddd.service=user-orchestrator \
  -Ddd.env=production \
  -Ddd.version=1.0.0 \
  -Ddd.trace.otel.enabled=true \
  -Ddd.data.streams.enabled=true \
  -Ddd.logs.injection=true \
  -Ddd.profiling.enabled=true \
  -jar user-orchestrator.jar
```

---

## 🛡️ 8. Garantias de Não-Regressão e Portabilidade

1. **Código 100% Livre de Código Proprietário**:
   - O código Java das aplicações usuárias depende unicamente das anotações `@TrackFlow`, `@TrackStep`, `@ObservationTag` e da interface `FeatureEvaluationListener`.
   - Nenhuma migração de versão do Datadog Agent ou troca para outro provedor requer alterações no código-fonte das aplicações.
2. **Ambiente Local e CI 100% Autônomos**:
   - `mvn test` executa localmente utilizando o `MicrometerObservabilityEngine`, sem exigir containers de Datadog Agent nem envio de dados para a nuvem.
3. **Respeito aos Contratos de SLAs e Alarmística**:
   - Os alarmes disparados por `AlertDispatcher` (`FLOW_LATENCY_SLA_BREACH`, `INTEGRATION_LATENCY_SLA_BREACH`, `FLOW_STEP_INTERRUPTION`) continuam funcionando de forma idêntica em ambas as engines.
