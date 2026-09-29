# Cenários de Teste, Caos e Validação

Este documento explica como simular cenários de alta carga e engenharia de caos para validar a resiliência e a observabilidade da aplicação.

---

## 🌪️ 1. Mapeamentos de Caos no WireMock

O WireMock (porta `8081`) está configurado com diferentes perfis de comportamento por usuário:

| Usuário / Path | Comportamento Simulado | Efeito Observado no Sistema |
| :--- | :--- | :--- |
| `user1` até `user20` | Resposta imediata 200 OK com dados válidos e tipos de plano distribuídos (`FREE`, `MONTHLY`, `YEARLY`, `TRIAL`, `LIFETIME`). | Operação saudável (200 OK), latência baixa (~15ms), métricas normais. |
| `/customers/slow` | Atraso artificial de **3.000ms** com resposta 200 OK. | Estoura o `readTimeout: 2000ms` do Feign, gerando `RetryableException` e acionando o Circuit Breaker de `customer-service`. |
| `/billing/accounts/error` | Resposta imediata **500 Internal Server Error**. | Registra falha no Circuit Breaker de `billing-service`. Com >50% de falhas, o disjuntor abre (`OPEN`). |
| `/notifications` (`flake`) | Alternância entre sucesso e falha (cenário intermitente). | Incrementa a taxa de erro de notificações e testa a tolerância a falhas transitórias. |

---

## 🚀 2. Execução da Carga Contínua (`massive_load.py`)

O script [`massive_load.py`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/massive_load.py) foi construído em Python com um pool de threads assíncronas para gerar carga contínua sem travar o terminal:

```python
# Composição do tráfego:
# - 15 chamadas GET /api/v1/orchestrator/users/{userId} (Síncrono E2E)
# - 5 chamadas POST /api/v1/orchestrator/users (Assíncrono via Kafka -> SQS)
```

### Como Executar em Background
```bash
python3 massive_load.py
```

### O que observar durante o teste:
1. **Pico de Latência E2E**: Quando o usuário `slow` é sorteado, a curva do painel de latência E2E sobe para a faixa de ~2.5s.
2. **Fatia de Latência do Feign**: A linha do `customer-service` sobe significativamente acima de `billing-service` e `notification-service`.
3. **Abertura do Disjuntor**: Quando o volume de chamadas para `error` ou `slow` atinge o limiar configurado (`slidingWindowSize: 10`, `failureRateThreshold: 50%`), o card correspondente muda para `🔴 ABERTO`.
4. **Engarrafamento e Drenagem no SQS**: Como o consumidor de auditoria simula um tempo de processamento mais alto, a profundidade das filas no SQS sobe em rampa e estabiliza à medida que as mensagens são consumidas.

---

## 🔍 3. Rastreamento Distribuído no Jaeger (Traces OTLP)

A aplicação exporta spans via padrão OpenTelemetry (porta `4318` do Jaeger):

1. Acesse a interface do Jaeger: [http://localhost:16686](http://localhost:16686).
2. Selecione o serviço: `user-orchestrator`.
3. Clique em **Find Traces**.
4. Ao inspecionar um trace de `fetchAndProvisionUserProfile`, você verá a árvore hierárquica:
   - Span raiz: `http.server.requests` (requisição GET de entrada)
   - Span filho: `user.profile.provision`
     - Span Feign: `http.client.requests` (`GET /customers/{userId}`)
     - Span Feign: `http.client.requests` (`GET /billing/accounts/{userId}`)
     - Span Feign: `http.client.requests` (`POST /notifications`)
     - Span SQS: `messaging.produce` (`sqs-user-audit-produce`)
     - Span Kafka: `messaging.produce` (`kafka-billing-produce`)

Cada span carrega as tags de negócio injetadas pelo nosso aspecto (`userId`, `billing_type`, etc.), permitindo filtrar no Jaeger todos os rastros de um tipo específico de cliente ou erro.

---

## 🌪️ 4. Cenários de Caos em Banco de Dados Relacional (PostgreSQL / HikariCP)

Implementamos um controlador dedicado (`DatabaseChaosController`) para injetar cenários de lentidão e falhas no pool de conexões do banco de dados PostgreSQL configurado na aplicação.

### 4.1. Cenário Base (Operação Normal)

Para ter um *baseline* (linha de base) das métricas de tempo de execução (latência) e uso do pool, você pode realizar chamadas normais:

**Comando:**
```bash
# Grava uma transação simples no banco
curl -X POST "http://localhost:8080/api/db-chaos/normal?amount=250.0"

# Busca transações gravadas
curl -X GET "http://localhost:8080/api/db-chaos/normal"
```
**O que observar:** O tempo da requisição deve ser rápido (< 50ms) e o pool de conexões (visível nas métricas `hikaricp_connections_active_total`) deve apenas piscar em `1` e logo voltar a `0` quando a conexão for devolvida ao pool.

### 4.2. Cenário de Lentidão (Slow Query)

Uma transação pode demorar para ser executada (por lock de tabela, processamento complexo, gargalo de disco, etc.). Este cenário simula uma "slow query" amarrando a conexão por N segundos.

**Comando:**
```bash
# Segura a conexão de banco de dados por 8 segundos
curl -X GET "http://localhost:8080/api/db-chaos/slow-query?delaySeconds=8"
```

**Como observar a métrica:**
- **Prometheus/Grafana:** A métrica `hikaricp_connections_active_total` ficará no valor `1` durante os 8 segundos.
- **Tracing (Jaeger):** Ao procurar esse Trace, o *span* de banco de dados (capturado pelo Hibernate interceptor) mostrará uma duração excessiva.

### 4.3. Exaustão do Pool de Conexões (Connection Pool Exhaustion)

O HikariCP está configurado com um pool bem restrito (`maximum-pool-size: 5`) e um timeout muito curto (`connection-timeout: 3000ms`). Isso nos permite facilmente simular a exaustão do pool (quando não há conexões disponíveis para novas requisições).

**Comando:**
```bash
# Dispara 12 requisições concorrentes em background
# Cada uma vai segurar 1 conexão por 10 segundos
curl -X POST "http://localhost:8080/api/db-chaos/exhaust-pool?concurrentRequests=12&holdTimeSeconds=10"
```

**Como observar a falha e o caos:**
1. **Métricas de Conexão:** A métrica `hikaricp_connections_active_total` atingirá o topo (`5`) rapidamente e permanecerá bloqueada.
2. **Métricas de Fila:** A métrica `hikaricp_connections_pending_total` saltará, indicando que há threads em fila de espera implorando por uma conexão.
3. **Métricas de Timeout:** Como a fila ultrapassará os 3 segundos de limite configurado no `application.yml`, o HikariCP começará a rejeitar threads com `SQLTransientConnectionException`. Isso refletirá na métrica `hikaricp_connections_timeout_total`.
4. **Logs da Aplicação:** O console será bombardeado com os logs de erro de tempo excedido aguardando o recurso.
