package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserOrchestratorE2EObservabilityIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry;

    private ListAppender<ILoggingEvent> logAppender;
    private final List<Observation.Context> finishedObservationContexts = new CopyOnWriteArrayList<>();
    private ObservationHandler<Observation.Context> testObservationHandler;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.options()
                        .dynamicPort()
                        .usingFilesUnderDirectory("wiremock")
        );
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("wiremock.port", wireMockServer::port);
        registry.add("app.integrations.customer.url", wireMockServer::baseUrl);
        registry.add("app.integrations.billing.url", wireMockServer::baseUrl);
        registry.add("app.integrations.notification.url", wireMockServer::baseUrl);
    }

    @BeforeEach
    void setUp() {
        // Resetar Circuit Breakers para estado limpo
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);

        // 1. Configurar coletor de logs no Root Logger para capturar aplicação e AUDIT_LEG_LOGGER
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.setContext(rootLogger.getLoggerContext());
        logAppender.start();
        rootLogger.addAppender(logAppender);

        // 2. Configurar coletor de traces/observações
        finishedObservationContexts.clear();
        testObservationHandler = new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                finishedObservationContexts.add(context);
            }
        };
        observationRegistry.observationConfig().observationHandler(testObservationHandler);
    }

    @AfterEach
    void tearDown() {
        if (logAppender != null) {
            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            rootLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    @DisplayName("E2E Entrypoint: Deve executar GET /users/{userId}, coletando payload, logs, métricas e traces")
    void shouldExecuteSyncEntrypointAndCollectAllTelemetry() throws Exception {
        String userId = "user1";

        // 1. Executa a chamada HTTP ponta a ponta no entrypoint
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", equalTo("user1")))
                .andExpect(jsonPath("$.customer.name", equalTo("John Doe")))
                .andExpect(jsonPath("$.customer.email", equalTo("johndoe@example.com")))
                .andExpect(jsonPath("$.billing.plan", equalTo("Enterprise")))
                .andExpect(jsonPath("$.billing.billingType", equalTo("MONTHLY")))
                .andExpect(jsonPath("$.notificationStatus", equalTo("DELIVERED")));

        // 2. COLETA E ASSERÇÃO DE TODOS OS LOGS CAPTURADOS PELO CAMINHO
        List<ILoggingEvent> capturedLogs = logAppender.list;
        assertThat(capturedLogs).isNotEmpty();

        List<String> logMessages = capturedLogs.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // A) Verifica logs do LoggingObservationHandler (Início e Fim das Operações)
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("Starting operation: flow.get.api.v1.orchestrator.users.userid"))
                .anyMatch(msg -> msg.contains("Starting operation: user.profile.provision"))
                .anyMatch(msg -> msg.contains("Starting operation: step.api.customer.get.customers.userid"))
                .anyMatch(msg -> msg.contains("Finished operation: step.api.customer.get.customers.userid"))
                .anyMatch(msg -> msg.contains("Starting operation: step.api.billing.get.billing.accounts.userid"))
                .anyMatch(msg -> msg.contains("Finished operation: step.api.billing.get.billing.accounts.userid"))
                .anyMatch(msg -> msg.contains("Starting operation: step.api.notifica"))
                .anyMatch(msg -> msg.contains("Finished operation: step.api.notifica"))
                .anyMatch(msg -> msg.contains("Finished operation: user.profile.provision"))
                .anyMatch(msg -> msg.contains("Finished operation: flow.get.api.v1.orchestrator.users.userid"));

        // B) Verifica logs de auditoria estruturada das Legs (AUDIT_LEG_LOGGER) e mascaramento de dados sensíveis
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("[LEG 1][INBOUND][REQUEST]") && msg.contains("user-orchestrator"))
                .anyMatch(msg -> msg.contains("[LEG 2][OUTBOUND][REQUEST]") && msg.contains("customer-service"))
                .anyMatch(msg -> msg.contains("[LEG 2][OUTBOUND][RESPONSE]") && msg.contains("customer-service") && msg.contains("j***e@example.com")) // EMAIL_PARTIAL
                .anyMatch(msg -> msg.contains("[LEG 3][OUTBOUND][REQUEST]") && msg.contains("billing-service"))
                .anyMatch(msg -> msg.contains("[LEG 3][OUTBOUND][RESPONSE]") && msg.contains("billing-service") && msg.contains("***REDACTED***")) // FULL_MASK
                .anyMatch(msg -> msg.contains("[LEG 4][OUTBOUND][REQUEST]") && msg.contains("notification-service"))
                .anyMatch(msg -> msg.contains("[LEG 4][OUTBOUND][RESPONSE]") && msg.contains("notification-service"))
                .anyMatch(msg -> msg.contains("[LEG 1][INBOUND][RESPONSE]") && msg.contains("user-orchestrator") && msg.contains("SUCCESS"));

        // C) Verifica presença de metadados MDC nas entradas de log da perna de auditoria
        assertThat(capturedLogs.stream()
                .filter(e -> "AUDIT_LEG_LOGGER".equals(e.getLoggerName()))
                .allMatch(e -> e.getMDCPropertyMap().containsKey("leg_number")
                        && e.getMDCPropertyMap().containsKey("leg_type")
                        && e.getMDCPropertyMap().containsKey("leg_target")
                        && e.getMDCPropertyMap().containsKey("leg_phase")))
                .isTrue();

        // Nenhum erro deve ter sido logado no fluxo sadio
        boolean hasErrors = capturedLogs.stream().anyMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(hasErrors).isFalse();

        // 3. COLETA E ASSERÇÃO DE TODAS AS MÉTRICAS CAPTURADAS PELO CAMINHO
        // A) Métrica total de duração do fluxo (E2E)
        Timer totalFlowTimer = meterRegistry.get("flow_total_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .timer();
        assertThat(totalFlowTimer).isNotNull();
        assertThat(totalFlowTimer.count()).isGreaterThanOrEqualTo(1);
        double totalFlowNanos = totalFlowTimer.totalTime(TimeUnit.NANOSECONDS);
        assertThat(totalFlowNanos).isGreaterThan(0);

        // B) Métricas fatiadas por step (Decomposição matemática de latência)
        Timer customerStepTimer = meterRegistry.get("flow_slice_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("step", "API Customer (GET /customers/{userId})")
                .timer();
        assertThat(customerStepTimer).isNotNull();
        assertThat(customerStepTimer.count()).isGreaterThanOrEqualTo(1);
        double customerNanos = customerStepTimer.totalTime(TimeUnit.NANOSECONDS);

        Timer billingStepTimer = meterRegistry.get("flow_slice_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("step", "API Billing (GET /billing/accounts/{userId})")
                .timer();
        assertThat(billingStepTimer).isNotNull();
        assertThat(billingStepTimer.count()).isGreaterThanOrEqualTo(1);
        double billingNanos = billingStepTimer.totalTime(TimeUnit.NANOSECONDS);

        Timer notificationStepTimer = meterRegistry.get("flow_slice_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("step", "API Notificação (POST /notifications)")
                .timer();
        assertThat(notificationStepTimer).isNotNull();
        assertThat(notificationStepTimer.count()).isGreaterThanOrEqualTo(1);
        double notifNanos = notificationStepTimer.totalTime(TimeUnit.NANOSECONDS);

        Timer internalProcessingTimer = meterRegistry.get("flow_slice_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("step", "Processamento Interno & Regras")
                .timer();
        assertThat(internalProcessingTimer).isNotNull();
        assertThat(internalProcessingTimer.count()).isGreaterThanOrEqualTo(1);
        double internalNanos = internalProcessingTimer.totalTime(TimeUnit.NANOSECONDS);

        // Prova matemática: A soma dos steps e processamento interno é igual ou superior ao tempo total
        double sumSlices = customerNanos + billingNanos + notifNanos + internalNanos;
        assertThat(sumSlices).isGreaterThanOrEqualTo(totalFlowNanos * 0.99); // Tolerância infinitesimal de precisão

        // C) Métricas do Resilience4j Circuit Breaker
        Timer customerTimer = meterRegistry.find("resilience4j.circuitbreaker.calls")
                .tag("name", "customer-service")
                .tag("kind", "successful")
                .timer();
        if (customerTimer != null) {
            assertThat(customerTimer.count()).isGreaterThanOrEqualTo(1);
        } else {
            assertThat(circuitBreakerRegistry.circuitBreaker("customer-service").getMetrics().getNumberOfSuccessfulCalls())
                    .isGreaterThanOrEqualTo(1);
        }

        Timer billingTimer = meterRegistry.find("resilience4j.circuitbreaker.calls")
                .tag("name", "billing-service")
                .tag("kind", "successful")
                .timer();
        if (billingTimer != null) {
            assertThat(billingTimer.count()).isGreaterThanOrEqualTo(1);
        } else {
            assertThat(circuitBreakerRegistry.circuitBreaker("billing-service").getMetrics().getNumberOfSuccessfulCalls())
                    .isGreaterThanOrEqualTo(1);
        }

        Timer notifTimer = meterRegistry.find("resilience4j.circuitbreaker.calls")
                .tag("name", "notification-service")
                .tag("kind", "successful")
                .timer();
        if (notifTimer != null) {
            assertThat(notifTimer.count()).isGreaterThanOrEqualTo(1);
        } else {
            assertThat(circuitBreakerRegistry.circuitBreaker("notification-service").getMetrics().getNumberOfSuccessfulCalls())
                    .isGreaterThanOrEqualTo(1);
        }

        // 4. COLETA E ASSERÇÃO DE TRACES E CONTEXTOS DE OBSERVAÇÃO
        assertThat(finishedObservationContexts).isNotEmpty();
        finishedObservationContexts.forEach(ctx -> {
            System.out.println("TEST_DEBUG: Observation name=" + ctx.getName()
                    + " | High=" + ctx.getHighCardinalityKeyValues()
                    + " | Low=" + ctx.getLowCardinalityKeyValues());
        });
        Observation.Context serviceObservation = finishedObservationContexts.stream()
                .filter(ctx -> "user.profile.provision".equals(ctx.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Observação 'user.profile.provision' não encontrada"));
        assertThat(serviceObservation.getLowCardinalityKeyValues())
                .anyMatch(kv -> "method".equals(kv.getKey()) && "fetchAndProvisionUserProfile".equals(kv.getValue()));

        // Contexto: flow.get.api.v1.orchestrator.users.userid (Entrypoint Flow enriquecido pelo aspecto SpEL)
        Observation.Context flowObservation = finishedObservationContexts.stream()
                .filter(ctx -> "flow.get.api.v1.orchestrator.users.userid".equals(ctx.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Observação do flow entrypoint não encontrada"));

        // Asserção das tags SpEL extraídas dinamicamente via safe-navigation (#userId, #result?.billing()?.plan())
        assertThat(flowObservation.getHighCardinalityKeyValues())
                .anyMatch(kv -> "userId".equals(kv.getKey()) && "user1".equals(kv.getValue()));

        assertThat(flowObservation.getLowCardinalityKeyValues())
                .anyMatch(kv -> "customer_plan".equals(kv.getKey()) && "Enterprise".equals(kv.getValue()))
                .anyMatch(kv -> "flow".equals(kv.getKey()) && "provisioning".equals(kv.getValue()));

        // Contexto: step.api.customer.get.customers.userid (Feign Client)
        Observation.Context customerObservation = finishedObservationContexts.stream()
                .filter(ctx -> "step.api.customer.get.customers.userid".equals(ctx.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Observação do step Customer não encontrada"));

        assertThat(customerObservation.getLowCardinalityKeyValues())
                .anyMatch(kv -> "flow".equals(kv.getKey()) && "GET /api/v1/orchestrator/users/{userId}".equals(kv.getValue()))
                .anyMatch(kv -> "step".equals(kv.getKey()) && "API Customer (GET /customers/{userId})".equals(kv.getValue()))
                .anyMatch(kv -> "client".equals(kv.getKey()) && "customer".equals(kv.getValue()));

        // Contexto: step.api.billing.get.billing.accounts.userid (Feign Client com SpEL #result)
        Observation.Context billingObservation = finishedObservationContexts.stream()
                .filter(ctx -> "step.api.billing.get.billing.accounts.userid".equals(ctx.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Observação do step Billing não encontrada"));

        assertThat(billingObservation.getLowCardinalityKeyValues())
                .anyMatch(kv -> "client".equals(kv.getKey()) && "billing".equals(kv.getValue()))
                .anyMatch(kv -> "billing_type".equals(kv.getKey()) && "MONTHLY".equals(kv.getValue()));

        // Contexto: step.api.notificacao.post.notifications (sanitizado para notifica.o)
        Observation.Context notifObservation = finishedObservationContexts.stream()
                .filter(ctx -> ctx.getName().startsWith("step.api.notifica"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Observação do step Notificação não encontrada"));

        assertThat(notifObservation.getLowCardinalityKeyValues())
                .anyMatch(kv -> "client".equals(kv.getKey()) && "notification".equals(kv.getValue()));
    }
}
