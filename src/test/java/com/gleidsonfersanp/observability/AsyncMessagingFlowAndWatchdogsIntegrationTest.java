package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gleidsonfersanp.observability.domain.AuditLogRepository;
import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertEvent;
import com.empresa.platform.observability.core.alerting.AlertSeverity;
import com.empresa.platform.observability.core.alerting.AlertType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AsyncMessagingFlowAndWatchdogsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AlertDispatcher alertDispatcher;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        alertDispatcher.clearAlerts();

        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (rootLogger != null && logAppender != null) {
            rootLogger.detachAppender(logAppender);
        }
    }

    @Test
    @DisplayName("Cenário 1: Jornada de Entrypoint Assíncrono (POST /users) com Decomposição de Latência, Masking de Legs e Métricas")
    void testAsyncRegistrationEntrypointJourneyAndTelemetry() throws Exception {
        String requestJson = """
                {
                    "userId": "user-async-10",
                    "name": "Maria Silva",
                    "email": "maria.silva@example.com"
                }
                """;

        // 1. Executa o entrypoint assíncrono
        mockMvc.perform(post("/api/v1/orchestrator/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.message").value("User registration initiated asynchronously for user-async-10"));

        // 2. Coleta e validação exaustiva de logs
        List<ILoggingEvent> events = logAppender.list;
        List<String> messages = events.stream().map(ILoggingEvent::getFormattedMessage).toList();

        // 2.1 Verifica log estruturado da perna INBOUND do entrypoint assíncrono
        List<ILoggingEvent> legEvents = events.stream()
                .filter(e -> "AUDIT_LEG_LOGGER".equals(e.getLoggerName()))
                .toList();
        assertThat(legEvents).isNotEmpty();

        // Valida que o log da perna INBOUND REQUEST foi emitido
        assertThat(messages).anyMatch(m -> m.contains("[LEG 1][INBOUND][REQUEST]")
                && m.contains("user-orchestrator.registerUser()"));

        // Valida Masking no log de auditoria: maria.silva@example.com deve ser mascarado para m***a@example.com
        assertThat(messages).anyMatch(m -> m.contains("m***a@example.com"));

        // Valida que o email em texto puro NÃO vazou no log da perna mascarada
        boolean rawEmailLeakedInLeg = legEvents.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("\"email\":\"maria.silva@example.com\""));
        assertThat(rawEmailLeakedInLeg).isFalse();

        // 2.2 Verifica logs de ciclo de vida da observação via LoggingObservationHandler
        assertThat(messages).anyMatch(m -> m.contains("Starting operation: flow.post.api.v1.orchestrator.users"));
        assertThat(messages).anyMatch(m -> m.contains("Finished operation: flow.post.api.v1.orchestrator.users"));
        assertThat(messages).anyMatch(m -> m.contains("Starting operation: step.publica.o.kafka.user.registration.topic"));
        assertThat(messages).anyMatch(m -> m.contains("Finished operation: step.publica.o.kafka.user.registration.topic"));

        // 3. Validação das métricas de decomposição de latência (FlowContext + Micrometer)
        // 3.1 Duração total do fluxo
        Timer flowTimer = meterRegistry.find("flow_total_duration_seconds")
                .tag("flow", "POST /api/v1/orchestrator/users")
                .timer();
        assertThat(flowTimer).isNotNull();
        assertThat(flowTimer.count()).isGreaterThanOrEqualTo(1);

        // 3.2 Slice do step de publicação Kafka
        Timer stepTimer = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "POST /api/v1/orchestrator/users")
                .tag("step", "Publicação Kafka (user-registration-topic)")
                .timer();
        assertThat(stepTimer).isNotNull();
        assertThat(stepTimer.count()).isGreaterThanOrEqualTo(1);

        // 3.3 Slice de processamento interno residual
        Timer internalTimer = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", "POST /api/v1/orchestrator/users")
                .tag("step", "Processamento Interno & Regras")
                .timer();
        assertThat(internalTimer).isNotNull();
        assertThat(internalTimer.count()).isGreaterThanOrEqualTo(1);

        // 3.4 Prova matemática da decomposição do fluxo assíncrono:
        // A soma dos slices deve recompor a duração total do fluxo
        double totalFlowSeconds = flowTimer.totalTime(java.util.concurrent.TimeUnit.SECONDS);
        double stepSliceSeconds = stepTimer.totalTime(java.util.concurrent.TimeUnit.SECONDS);
        double internalSliceSeconds = internalTimer.totalTime(java.util.concurrent.TimeUnit.SECONDS);
        double slicesSum = stepSliceSeconds + internalSliceSeconds;

        assertThat(slicesSum).isCloseTo(totalFlowSeconds, org.assertj.core.data.Offset.offset(0.015));
    }

    @Test
    @DisplayName("Cenário 2: Watchdog do Kafka detecta acúmulo de Lag, dispara alerta e incrementa métrica de alarme")
    void testKafkaLagWatchdogAlertAndCounter() throws Exception {
        // Dispara um alerta de acúmulo de Lag no Kafka simulando a ultrapassagem do limiar (threshold 80)
        AlertEvent lagAlert = AlertEvent.of(
                AlertType.KAFKA_LAG_HIGH,
                AlertSeverity.WARNING,
                "KafkaLagBinder",
                "user-registration-topic-user-orchestrator-group",
                "Lag no tópico 'user-registration-topic' (grupo 'user-orchestrator-group') atingiu 145 mensagens (limiar: 80).",
                145.0,
                80.0,
                Map.of("topic", "user-registration-topic", "group", "user-orchestrator-group", "lag", 145, "threshold", 80)
        );

        alertDispatcher.dispatch(lagAlert);

        // Valida que o alerta foi registrado na memória in-app
        List<AlertEvent> alerts = alertDispatcher.getRecentAlerts();
        assertThat(alerts).anyMatch(a -> a.type() == AlertType.KAFKA_LAG_HIGH
                && a.severity() == AlertSeverity.WARNING
                && a.source().equals("KafkaLagBinder"));

        // Valida incremento da métrica do Micrometer alerts_triggered_total
        Counter counter = meterRegistry.find("alerts_triggered_total")
                .tag("type", "KAFKA_LAG_HIGH")
                .tag("severity", "WARNING")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);

        // Valida endpoint REST de consulta de alertas (/api/v1/orchestrator/alerts)
        mockMvc.perform(get("/api/v1/orchestrator/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[?(@.type == 'KAFKA_LAG_HIGH')].severity").value(hasItem("WARNING")));
    }

    @Test
    @DisplayName("Cenário 3: Watchdog do SQS detecta profundidade excessiva de fila, dispara alerta e incrementa métrica")
    void testSqsBacklogWatchdogAlertAndCounter() throws Exception {
        // Dispara um alerta de backlog excessivo no SQS simulando ultrapassagem do limiar (threshold 40)
        AlertEvent sqsAlert = AlertEvent.of(
                AlertType.SQS_BACKLOG_HIGH,
                AlertSeverity.WARNING,
                "SqsMetricsBinder",
                "customer-events-queue",
                "Backlog na fila SQS 'customer-events-queue' atingiu 65 mensagens (limiar: 40).",
                65.0,
                40.0,
                Map.of("queue", "customer-events-queue", "depth", 65, "threshold", 40)
        );

        alertDispatcher.dispatch(sqsAlert);

        // Valida que o alerta foi registrado na memória in-app
        List<AlertEvent> alerts = alertDispatcher.getRecentAlerts();
        assertThat(alerts).anyMatch(a -> a.type() == AlertType.SQS_BACKLOG_HIGH
                && a.severity() == AlertSeverity.WARNING
                && a.source().equals("SqsMetricsBinder"));

        // Valida incremento da métrica do Micrometer alerts_triggered_total
        Counter counter = meterRegistry.find("alerts_triggered_total")
                .tag("type", "SQS_BACKLOG_HIGH")
                .tag("severity", "WARNING")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);

        // Valida endpoint REST de alertas
        mockMvc.perform(get("/api/v1/orchestrator/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[?(@.type == 'SQS_BACKLOG_HIGH')].severity").value(hasItem("WARNING")));
    }

    @Test
    @DisplayName("Cenário 4: Validação do repositório e endpoint de auditoria consumido por workers SQS")
    void testAuditLogRepositoryEndpoint() throws Exception {
        String auditMessage = "[USER_REGISTERED] User: user-async-audit - Profile status: DELIVERED";
        auditLogRepository.addLog(auditMessage);

        mockMvc.perform(get("/api/v1/orchestrator/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem(containsString("user-async-audit"))));
    }
}
