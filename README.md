# Observability & Resilience Example
Um projeto completo demonstrando microsserviços integrados com Spring Boot 3, Kafka, SQS (LocalStack), WireMock, Micrometer, Resilience4j, Prometheus, Grafana e Jaeger!

## 📚 Documentação Completa
A documentação detalhada da arquitetura, observabilidade e engenharia de caos está disponível na pasta [`documentacao/`](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/README.md):
- [**Visão Geral e Arquitetura**](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/README.md)
- [**Como Metrificar por Stack Tecnológica**](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/GUIA_METRIFICACAO_DAS_STACKS.md)
- [**Catálogo de Métricas e Consultas PromQL do Dashboard**](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/CATALOGO_DE_METRICAS_E_QUERIES_DASHBOARD.md)
- [**Registro de Decisões Arquiteturais (ADRs)**](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/DECISOES_ARQUITETURAIS_ADR.md)
- [**Guia de Alarmística, SLAs e Incidentes**](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/GUIA_DE_ALARMISTICA_E_SLAS.md)
- [**Guia de Observabilidade, SpEL e Métricas**](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/OBSERVABILIDADE_E_METRICAS.md)
- [**Cenários de Teste, Caos e Validação**](file:///Users/gleidsonfersanp/workspace/spring-observability-example/documentacao/CENARIOS_DE_TESTE_E_CAOS.md)

## Como Rodar o Ambiente
Suba todos os serviços base:
```bash
docker-compose up -d
```
Aguarde alguns segundos e acesse as ferramentas de observabilidade:
- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **Jaeger (Traces)**: http://localhost:16686

## Chaos Engineering / Simulações de Falhas
- **Timeout**: `curl http://localhost:8080/api/v1/orchestrator/users/slow`
- **Erro 500**: `curl http://localhost:8080/api/v1/orchestrator/users/error`
- **Flaky**: `curl http://localhost:8080/api/v1/orchestrator/users/flake`

## Load Test e Distribuição de BillingTypes
Acabei de atualizar o WireMock criando **20 cenários diferentes de clientes** (user1 até user20), cada um com um `billingType` aleatório (MONTHLY, YEARLY, FREE, TRIAL, etc).

Para ver o dashboard de proporção ser populado no Grafana, dispare essa carga:
```bash
# Reinicie o WireMock para carregar os novos usuários
curl -X POST http://localhost:8081/__admin/mappings/reset

# Dispare 100 requisições simuladas
for i in {1..100}; do 
  USER_ID=$(( (RANDOM % 20) + 1 ))
  curl -s -o /dev/null -w "User $USER_ID: %{http_code}\n" http://localhost:8080/api/v1/orchestrator/users/user$USER_ID
done
```

## Como montar o gráfico Pie Chart no Grafana:
Crie um novo Dashboard, adicione um Painel do tipo "Pie Chart" e cole a query PromQL:
`sum(rate(user_profile_provision_seconds_count[1m])) by (billing_type)`

## Teste de Carga Massiva (Stress Test)
Para gerar uma enxurrada de requisições, encher as filas do Kafka/SQS e ver os disjuntores abrindo em tempo real no Grafana, criei um script Python multithread. Ele enviará 1000 requisições concorrentes:
```bash
python3 massive_load.py
```
