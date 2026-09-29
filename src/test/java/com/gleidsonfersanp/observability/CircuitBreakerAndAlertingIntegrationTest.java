package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.gleidsonfersanp.observability.domain.UserProfile;
import com.gleidsonfersanp.observability.observability.alerting.AlertDispatcher;
import com.gleidsonfersanp.observability.observability.alerting.AlertEvent;
import com.gleidsonfersanp.observability.observability.alerting.AlertSeverity;
import com.gleidsonfersanp.observability.observability.alerting.AlertType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CircuitBreakerAndAlertingIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private AlertDispatcher alertDispatcher;

    private ListAppender<ILoggingEvent> logAppender;

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
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        alertDispatcher.clearAlerts();

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.gleidsonfersanp.observability");
        logAppender = new ListAppender<>();
        logAppender.setContext(logger.getLoggerContext());
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (logAppender != null) {
            logAppender.stop();
        }
    }

    @Test
    @DisplayName("Caos HTTP 500: Falhas no BillingService devem abrir o Circuit Breaker, acionar fallback e disparar Alerta CRITICAL")
    void shouldOpenCircuitBreakerOnFailuresAndDispatchAlert() throws Exception {
        CircuitBreaker billingCb = circuitBreakerRegistry.circuitBreaker("billing-service");
        assertThat(billingCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Faz 6 chamadas com userId "error" (mapeado no wiremock billing-error.json para retornar 500)
        // Isso atinge o minimumNumberOfCalls (5) e failureRateThreshold (50%)
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "error"));
        }

        // 1. Prova a transição de estado do Circuit Breaker do billing-service
        assertThat(billingCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 2. Prova a interceptação não-intrusiva do CircuitBreakerAlertListener e despacho para o AlertDispatcher
        List<AlertEvent> recentAlerts = alertDispatcher.getRecentAlerts();
        assertThat(recentAlerts).isNotEmpty();

        AlertEvent cbAlert = recentAlerts.stream()
                .filter(a -> a.type() == AlertType.CIRCUIT_BREAKER_OPEN && "billing-service".equals(a.target()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Alerta de CIRCUIT_BREAKER_OPEN para billing-service não encontrado"));

        assertThat(cbAlert.severity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(cbAlert.source()).isEqualTo("Resilience4j");
        assertThat(cbAlert.message()).contains("Disjuntor 'billing-service' abriu (OPEN)");

        // 3. Prova o incremento do contador de métricas do Prometheus para o alerta
        Counter alertCounter = meterRegistry.find("alerts_triggered_total")
                .tag("type", AlertType.CIRCUIT_BREAKER_OPEN.name())
                .tag("severity", AlertSeverity.CRITICAL.name())
                .tag("target", "billing-service")
                .counter();
        assertThat(alertCounter).isNotNull();
        assertThat(alertCounter.count()).isGreaterThanOrEqualTo(1);

        // 4. Prova a consulta via endpoint GET /api/v1/orchestrator/alerts
        mockMvc.perform(get("/api/v1/orchestrator/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'CIRCUIT_BREAKER_OPEN' && @.target == 'billing-service')]").exists());

        // 5. Prova a execução do fallback do orquestrador quando o disjuntor abre
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", equalTo("error")))
                .andExpect(jsonPath("$.customer.name", equalTo("Unknown (Fallback)")))
                .andExpect(jsonPath("$.billing.plan", equalTo("Unknown Plan")))
                .andExpect(jsonPath("$.notificationStatus", equalTo("FAILED_DUE_TO_FALLBACK")));
    }

    @Test
    @DisplayName("SLA Breach: Latência em step Feign excedendo limiar estipulado deve disparar Alerta INTEGRATION_LATENCY_SLA_BREACH")
    void shouldDispatchWarningAlertWhenStepExceedsLatencySla() throws Exception {
        // Step SLA para "API Customer" está configurado em application.yml como 500ms
        // Criamos um stub WireMock dinâmico para /customers/sla-breach com atraso de 650ms
        wireMockServer.stubFor(WireMock.get(urlEqualTo("/customers/sla-breach"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(1150)
                        .withBody("{\"name\": \"SLA User\", \"email\": \"sla@example.com\"}")));

        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "sla-breach"))
                .andExpect(status().isOk());

        // Prova que o SLA Guard do FlowTrackingAspect detectou a violação de latência do step
        List<AlertEvent> recentAlerts = alertDispatcher.getRecentAlerts();
        assertThat(recentAlerts)
                .anyMatch(a -> a.type() == AlertType.INTEGRATION_LATENCY_SLA_BREACH
                        && a.severity() == AlertSeverity.WARNING
                        && a.target().contains("API Customer")
                        && a.currentValue() >= 500);

        Counter slaAlertCounter = meterRegistry.find("alerts_triggered_total")
                .tag("type", AlertType.INTEGRATION_LATENCY_SLA_BREACH.name())
                .counter();
        assertThat(slaAlertCounter).isNotNull();
        assertThat(slaAlertCounter.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Flow Interruption: Interrupção em step deve registrar métrica flow_interruption_total e disparar Alerta")
    void shouldRecordFlowInterruptionMetricAndAlert() throws Exception {
        // Configura WireMock para falhar com 500 no customer
        wireMockServer.stubFor(WireMock.get(urlEqualTo("/customers/fatal-fail"))
                .willReturn(aResponse().withStatus(500).withBody("Fatal Error")));

        // Dispara requisição que causará erro no step
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "fatal-fail"));

        // Prova a emissão do alerta FLOW_STEP_INTERRUPTION
        List<AlertEvent> recentAlerts = alertDispatcher.getRecentAlerts();
        assertThat(recentAlerts)
                .anyMatch(a -> a.type() == AlertType.FLOW_STEP_INTERRUPTION
                        && a.severity() == AlertSeverity.CRITICAL
                        && a.target().contains("API Customer"));

        // Prova a métrica de interrupção
        Counter interruptionCounter = meterRegistry.find("flow_interruption_total")
                .tag("failed_step", "API Customer (GET /customers/{userId})")
                .counter();
        assertThat(interruptionCounter).isNotNull();
        assertThat(interruptionCounter.count()).isGreaterThanOrEqualTo(1);
    }
}
