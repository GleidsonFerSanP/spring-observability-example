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
