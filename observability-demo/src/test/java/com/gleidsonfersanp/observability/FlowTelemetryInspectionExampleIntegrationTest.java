package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.gleidsonfersanp.observability.api.UserOrchestratorController;
import com.gleidsonfersanp.observability.application.UserOrchestratorService;
import com.gleidsonfersanp.observability.domain.UserRegistrationRequest;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 🎓 Teste de Exemplo e Referência Canônica de Inspeção da Telemetria.
 * <p>
 * Demonstra como obter, inspecionar e validar programaticamente em testes de integração:
 * <ol>
 *   <li><b>Todas as métricas propagadas por um fluxo {@code @TrackFlow} e seus {@code @TrackStep}s</b>
 *       (duração wall-clock, esforço nominal, fatias atribuídas, overhead interno e catálogo geral).</li>
 *   <li><b>Todos os logs de auditoria forense das Pernas (Legs via {@code @LogLeg})</b>
 *       (sequência cronológica INBOUND/OUTBOUND, metadados no MDC e conformidade de mascaramento LGPD com {@code @MaskField}).</li>
 *   <li><b>O enriquecimento declarativo de contexto de logs via {@code @MDC}</b>
 *       (extração de parâmetro, extração dinâmica via SpEL, valores estáticos e garantia de Stack Semantics anti-leakage).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowTelemetryInspectionExampleIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    private ListAppender<ILoggingEvent> rootLogAppender;
    private Logger rootLogger;

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
    void setUpLogCapture() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogAppender = new ListAppender<>();
        rootLogAppender.setContext(rootLogger.getLoggerContext());
        rootLogAppender.start();
        rootLogger.addAppender(rootLogAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        if (rootLogger != null && rootLogAppender != null) {
            rootLogger.detachAppender(rootLogAppender);
            rootLogAppender.stop();
        }
        MDC.clear();
    }

    // =========================================================================================
    // 1. COMO OBTER TODAS AS MÉTRICAS PROPAGADAS POR UM FLUXO DO @TrackFlow
    // =========================================================================================

    @Test
    @Order(1)
    @DisplayName("MÉTRICAS: Deve obter e inspecionar todas as métricas emitidas pelo @TrackFlow e @TrackStep")
    void shouldInspectAllMetricsPropagatedByTrackFlow() throws Exception {
        String flowName = "GET /api/v1/orchestrator/users/{userId}";
        String userId = "user1";

        // 1. Dispara a requisição HTTP que aciona o entrypoint anotado com @TrackFlow
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId));

        // -------------------------------------------------------------------------------------
        // A) Obter a métrica principal de duração total do fluxo (Wall-Clock Duration)
        // -------------------------------------------------------------------------------------
        Timer flowTimer = meterRegistry.find("observability.flow.duration")
                .tag("flow", flowName)
                .tag("status", "SUCCESS")
                .timer();

        assertThat(flowTimer)
                .as("O timer canônico 'observability.flow.duration' deve existir para o fluxo executado")
                .isNotNull();
        assertThat(flowTimer.count()).isGreaterThanOrEqualTo(1);
        double flowDurationNanos = flowTimer.totalTime(TimeUnit.NANOSECONDS);
        assertThat(flowDurationNanos).isGreaterThan(0.0);

        System.out.println("📊 [Métrica Fluxo Principal]");
        System.out.printf("   Nome: %s | Status: SUCCESS | Execuções: %d | Tempo Total: %.2f ms%n",
                flowName, flowTimer.count(), flowTimer.totalTime(TimeUnit.MILLISECONDS));

        // -------------------------------------------------------------------------------------
        // B) Obter todas as métricas de esforço nominal por componente/step (Component Work Duration)
        // -------------------------------------------------------------------------------------
        Collection<Timer> stepWorkTimers = meterRegistry.find("observability.flow.component.work.duration")
                .tag("flow", flowName)
                .timers();

        assertThat(stepWorkTimers)
                .as("O fluxo deve registrar métricas de esforço para cada subprocesso @TrackStep")
                .isNotEmpty();

        System.out.println("📊 [Métricas de Esforço Nominal por Componente/Step (Component Work Duration)]");
        for (Timer stepTimer : stepWorkTimers) {
            String componentName = stepTimer.getId().getTag("component");
            String componentType = stepTimer.getId().getTag("type");
            double stepDurationMs = stepTimer.totalTime(TimeUnit.MILLISECONDS);
            System.out.printf("   -> Componente: '%s' | Tipo: %s | Duração: %.2f ms%n",
                    componentName, componentType, stepDurationMs);
        }

        // Verifica que os steps das integrações foram todos medidos na métrica canônica
        List<String> recordedComponents = stepWorkTimers.stream()
                .map(t -> t.getId().getTag("component"))
                .toList();
        assertThat(recordedComponents).contains(
                "API Customer (GET /customers/{userId})",
                "API Billing (GET /billing/accounts/{userId})",
                "API Notificação (POST /notifications)"
        );

        // Demonstração adicional: Como obter também as métricas legadas (flow_slice_duration_seconds com tag 'step')
        Timer legacyCustomerTimer = meterRegistry.find("flow_slice_duration_seconds")
                .tag("flow", flowName)
                .tag("step", "API Customer (GET /customers/{userId})")
                .timer();
        assertThat(legacyCustomerTimer)
                .as("A métrica legada flow_slice_duration_seconds com tag 'step' também deve estar disponível")
                .isNotNull();

        // -------------------------------------------------------------------------------------
        // C) Obter as fatias de latência normalizada atribuída ao relógio (Attributed Duration)
        // -------------------------------------------------------------------------------------
        Collection<Timer> stepAttributedTimers = meterRegistry.find("observability.flow.component.attributed.duration")
                .tag("flow", flowName)
                .timers();

        assertThat(stepAttributedTimers)
                .as("O LatencyAttributionEngine deve emitir métricas de latência atribuída")
                .isNotEmpty();

        double totalAttributedNanos = stepAttributedTimers.stream()
                .mapToDouble(t -> t.totalTime(TimeUnit.NANOSECONDS))
                .sum();

        // -------------------------------------------------------------------------------------
        // D) Obter o tempo não atribuído / overhead de processamento interno da aplicação
        // -------------------------------------------------------------------------------------
        Timer unattributedTimer = meterRegistry.find("observability.flow.unattributed.duration")
                .tag("flow", flowName)
                .timer();

        double unattributedNanos = (unattributedTimer != null) ? unattributedTimer.totalTime(TimeUnit.NANOSECONDS) : 0.0;
        System.out.printf("📊 [Overhead Interno Não Atribuído]: %.2f ms%n",
                unattributedNanos / 1_000_000.0);

        // -------------------------------------------------------------------------------------
        // E) Como filtrar e inspecionar o catálogo completo de métricas associadas ao fluxo
        // -------------------------------------------------------------------------------------
        List<Meter> allFlowMeters = meterRegistry.getMeters().stream()
                .filter(m -> Objects.equals(m.getId().getTag("flow"), flowName))
                .toList();

        assertThat(allFlowMeters)
                .as("Deve ser possível filtrar todos os medidores que contenham a tag do fluxo")
                .isNotEmpty();

        System.out.printf("📊 [Catálogo Geral]: %d medidores encontrados associados ao fluxo '%s'%n",
                allFlowMeters.size(), flowName);
    }

    // =========================================================================================
    // 2. COMO OBTER E INSPECIONAR OS LEGS DE AUDITORIA (@LogLeg) E MASCARAMENTO (@MaskField)
    // =========================================================================================

    @Test
    @Order(2)
    @DisplayName("LEGS: Deve obter e validar a sequência de pernas, metadados MDC e mascaramento LGPD")
    void shouldInspectAndValidateAllAuditLegs() throws Exception {
        String userId = "user1";

        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", userId))
                .andExpect(status().isOk());

        // 1. Filtrar eventos emitidos especificamente no logger canônico de auditoria de pernas
        List<ILoggingEvent> legLogs = rootLogAppender.list.stream()
                .filter(event -> "AUDIT_LEG_LOGGER".equals(event.getLoggerName()))
                .toList();

        assertThat(legLogs)
                .as("O AUDIT_LEG_LOGGER deve ter capturado eventos de entrada (INBOUND) e saída (OUTBOUND)")
                .isNotEmpty();

        System.out.println("🪵 [Auditoria Forense de Pernas - AUDIT_LEG_LOGGER]");
        for (ILoggingEvent legEvent : legLogs) {
            Map<String, String> mdc = legEvent.getMDCPropertyMap();
            System.out.printf("   [Perna %s] %-8s | Fase: %-8s | Alvo: %-20s | Duração: %sms | Status: %s%n",
                    mdc.get("leg_number"),
                    mdc.get("leg_type"),
                    mdc.get("leg_phase"),
                    mdc.get("leg_target"),
                    mdc.getOrDefault("leg_duration_ms", "0"),
                    mdc.getOrDefault("leg_status", "PENDING"));
        }

        // -------------------------------------------------------------------------------------
        // A) Validar a sequência cronológica dos saltos de rede
        // -------------------------------------------------------------------------------------
        List<String> formattedMessages = legLogs.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // 1. Perna INBOUND de entrada no orquestrador
        assertThat(formattedMessages)
                .anyMatch(msg -> msg.contains("[LEG 1][INBOUND][REQUEST]") && msg.contains("user-orchestrator"));

        // 2. Perna OUTBOUND para customer-service
        assertThat(formattedMessages)
                .anyMatch(msg -> msg.contains("[LEG 2][OUTBOUND][REQUEST]") && msg.contains("customer-service"))
                .anyMatch(msg -> msg.contains("[LEG 2][OUTBOUND][RESPONSE]") && msg.contains("customer-service"));

        // 3. Perna OUTBOUND para billing-service
        assertThat(formattedMessages)
                .anyMatch(msg -> msg.contains("[LEG 3][OUTBOUND][REQUEST]") && msg.contains("billing-service"))
                .anyMatch(msg -> msg.contains("[LEG 3][OUTBOUND][RESPONSE]") && msg.contains("billing-service"));

        // 4. Perna OUTBOUND para notification-service
        assertThat(formattedMessages)
                .anyMatch(msg -> msg.contains("[LEG 4][OUTBOUND][REQUEST]") && msg.contains("notification-service"))
                .anyMatch(msg -> msg.contains("[LEG 4][OUTBOUND][RESPONSE]") && msg.contains("notification-service"));

        // 5. Conclusão da Perna INBOUND no orquestrador
        assertThat(formattedMessages)
                .anyMatch(msg -> msg.contains("[LEG 1][INBOUND][RESPONSE]") && msg.contains("user-orchestrator") && msg.contains("SUCCESS"));

        // -------------------------------------------------------------------------------------
        // B) Validar conformidade de mascaramento de dados sensíveis (LGPD / PCI-DSS)
        // -------------------------------------------------------------------------------------
        // Na chamada de resposta do customer-service, o email deve ser mascarado com EMAIL_PARTIAL (j***e@example.com)
        assertThat(formattedMessages)
                .as("O email retornado deve estar mascarado parcialmente conforme a anotação @MaskField")
                .anyMatch(msg -> msg.contains("j***e@example.com"));

        // O email original em texto claro NUNCA deve vazar no log de pernas
        assertThat(formattedMessages)
                .as("O email em texto claro 'johndoe@example.com' não pode vazar em nenhum log de auditoria")
                .noneMatch(msg -> msg.contains("johndoe@example.com"));

        // Na resposta do billing-service, o plano deve ter sofrido FULL_MASK (***REDACTED***)
        assertThat(formattedMessages)
                .as("O plano de faturamento deve ter sido substituído por ***REDACTED*** via MaskPattern.FULL_MASK")
                .anyMatch(msg -> msg.contains("***REDACTED***"));

        // -------------------------------------------------------------------------------------
        // C) Validar que todos os logs de pernas contêm as propriedades canônicas de MDC
        // -------------------------------------------------------------------------------------
        assertThat(legLogs)
                .allMatch(event -> {
                    Map<String, String> mdc = event.getMDCPropertyMap();
                    return mdc.containsKey("leg_number")
                            && mdc.containsKey("leg_type")
                            && mdc.containsKey("leg_target")
                            && mdc.containsKey("leg_phase");
                });
    }

    // =========================================================================================
    // 3. COMO OBTER E VALIDAR O ENRIQUECIMENTO DE MDC (@MDC) E STACK SEMANTICS
    // =========================================================================================

    @Test
    @Order(3)
    @DisplayName("MDC: Deve validar injeção de parâmetros, valores estáticos e garantia de Stack Semantics")
    void shouldInspectMdcEnrichmentAndVerifyStackSemantics() throws Exception {
        String testUserId = "user-789";

        // -------------------------------------------------------------------------------------
        // Cenário A: Extração de PathVariable anotado com @MDC("userId")
        // -------------------------------------------------------------------------------------
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", testUserId))
                .andExpect(status().isOk());

        // Procura logs emitidos pelo UserOrchestratorController durante a chamada
        List<ILoggingEvent> controllerLogs = rootLogAppender.list.stream()
                .filter(e -> e.getLoggerName().contains("UserOrchestratorController"))
                .toList();

        assertThat(controllerLogs).isNotEmpty();
        ILoggingEvent controllerEvent = controllerLogs.get(0);
        Map<String, String> controllerMdc = controllerEvent.getMDCPropertyMap();

        System.out.println("🏷️ [MDC no Controller REST]");
        System.out.printf("   userId: %s | flow: %s | correlation_id: %s%n",
                controllerMdc.get("userId"),
                controllerMdc.get("flow"),
                controllerMdc.get("correlation_id"));

        assertThat(controllerMdc.get("userId"))
                .as("O MDC no controller deve conter o userId extraído automaticamente do @PathVariable")
                .isEqualTo(testUserId);

        assertThat(controllerMdc.get("flow"))
                .as("O MDC deve conter o nome do fluxo fornecido pelo @TrackFlow")
                .isEqualTo("GET /api/v1/orchestrator/users/{userId}");

        // Procura logs emitidos pelo UserOrchestratorService (onde @MDC(key = "flowType", value = "orchestrated-provisioning") está presente)
        List<ILoggingEvent> serviceLogs = rootLogAppender.list.stream()
                .filter(e -> e.getLoggerName().contains("UserOrchestratorService"))
                .toList();

        assertThat(serviceLogs).isNotEmpty();
        ILoggingEvent serviceEvent = serviceLogs.get(0);
        Map<String, String> serviceMdc = serviceEvent.getMDCPropertyMap();

        System.out.println("🏷️ [MDC no Serviço de Negócio]");
        System.out.printf("   userId: %s | flowType: %s | flow: %s%n",
                serviceMdc.get("userId"),
                serviceMdc.get("flowType"),
                serviceMdc.get("flow"));

        assertThat(serviceMdc.get("userId"))
                .as("O service deve herdar o userId presente na thread")
                .isEqualTo(testUserId);

        assertThat(serviceMdc.get("flowType"))
                .as("O service deve possuir a propriedade estática flowType injetada pelo @MDC")
                .isEqualTo("orchestrated-provisioning");

        // -------------------------------------------------------------------------------------
        // Cenário B: Extração via SpEL e valores estáticos no POST /users
        // -------------------------------------------------------------------------------------
        UserRegistrationRequest registrationRequest = new UserRegistrationRequest(
                "user-async-55",
                "Carlos Souza",
                "carlos@example.com"
        );

        mockMvc.perform(post("/api/v1/orchestrator/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationRequest)))
                .andExpect(status().isAccepted());

        List<ILoggingEvent> postControllerLogs = rootLogAppender.list.stream()
                .filter(e -> e.getLoggerName().contains("UserOrchestratorController")
                        && e.getFormattedMessage().contains("Registering user via controller"))
                .toList();

        assertThat(postControllerLogs).isNotEmpty();
        Map<String, String> postMdc = postControllerLogs.get(0).getMDCPropertyMap();

        System.out.println("🏷️ [MDC no POST com SpEL e Valor Estático]");
        System.out.printf("   userId (SpEL): %s | channel (estático): %s%n",
                postMdc.get("userId"),
                postMdc.get("channel"));

        assertThat(postMdc.get("userId"))
                .as("Deveria extrair dinamicamente '#request.userId' via SpEL")
                .isEqualTo("user-async-55");

        assertThat(postMdc.get("channel"))
                .as("Deveria conter o valor estático 'web' declarado em @MDC(key = 'channel', value = 'web')")
                .isEqualTo("web");

        // -------------------------------------------------------------------------------------
        // C) Validação de Stack Semantics: Zero vazamento de contexto na thread pós-execução!
        // -------------------------------------------------------------------------------------
        assertThat(MDC.get("userId"))
                .as("Ao término da execução do método anotado com @MDC, a chave 'userId' deve ser limpa da thread")
                .isNull();

        assertThat(MDC.get("flowType"))
                .as("Ao término da execução, a chave 'flowType' deve ser removida da thread")
                .isNull();

        assertThat(MDC.get("channel"))
                .as("Ao término da execução, a chave 'channel' deve ser removida da thread")
                .isNull();
    }

    // =========================================================================================
    // 4. COMO INSPECIONAR TELEMETRIA EM FLUXO ASSÍNCRONO / MENSAGERIA
    // =========================================================================================

    @Test
    @Order(4)
    @DisplayName("ASSÍNCRONO: Deve inspecionar métricas e pernas no fluxo POST com publicação Kafka")
    void shouldInspectMetricsAndLegsOnAsyncMessagingFlow() throws Exception {
        String asyncFlowName = "POST /api/v1/orchestrator/users";
        UserRegistrationRequest request = new UserRegistrationRequest("user-kafka-99", "Ana Clara", "ana@example.com");

        mockMvc.perform(post("/api/v1/orchestrator/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        // 1. Validar que a métrica do fluxo assíncrono foi computada
        Timer asyncFlowTimer = meterRegistry.find("observability.flow.duration")
                .tag("flow", asyncFlowName)
                .timer();

        assertThat(asyncFlowTimer)
                .as("O fluxo POST /api/v1/orchestrator/users deve gerar a métrica observability.flow.duration")
                .isNotNull();
        assertThat(asyncFlowTimer.count()).isGreaterThanOrEqualTo(1);

        // 2. Validar que o step de negócio 'initiate-async-registration' foi medido
        Timer businessStepTimer = meterRegistry.find("observability.flow.component.work.duration")
                .tag("flow", asyncFlowName)
                .tag("component", "initiate-async-registration")
                .tag("type", "BUSINESS")
                .timer();

        assertThat(businessStepTimer)
                .as("O step de negócio anotado com @TrackStep deve ter sua métrica de esforço registrada")
                .isNotNull();

        // 3. Validar log de auditoria INBOUND com mascaramento de email
        List<ILoggingEvent> postLegs = rootLogAppender.list.stream()
                .filter(e -> "AUDIT_LEG_LOGGER".equals(e.getLoggerName())
                        && e.getFormattedMessage().contains("registerUser"))
                .toList();

        assertThat(postLegs).isNotEmpty();
        assertThat(postLegs)
                .as("O email no payload do log de auditoria deve estar mascarado como 'a***a@example.com'")
                .anyMatch(e -> e.getFormattedMessage().contains("a***a@example.com"));
        assertThat(postLegs)
                .as("O email em texto claro não deve estar presente no log")
                .noneMatch(e -> e.getFormattedMessage().contains("ana@example.com"));

        System.out.println("✅ [Fluxo Assíncrono Validado com Sucesso!]");
    }
}
