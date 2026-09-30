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
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MdcEnrichmentIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger controllerLogger;
    private Logger serviceLogger;

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
        listAppender = new ListAppender<>();
        listAppender.start();

        controllerLogger = (Logger) LoggerFactory.getLogger(UserOrchestratorController.class);
        serviceLogger = (Logger) LoggerFactory.getLogger(UserOrchestratorService.class);

        controllerLogger.addAppender(listAppender);
        serviceLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        if (controllerLogger != null) {
            controllerLogger.detachAppender(listAppender);
        }
        if (serviceLogger != null) {
            serviceLogger.detachAppender(listAppender);
        }
        MDC.clear();
    }

    @Test
    @DisplayName("GET Flow: Deve injetar @MDC(\"userId\") de parâmetro e @MDC de método no SLF4J MDC e limpar ao final")
    void shouldEnrichMdcViaParameterAndMethodInRealHttpFlow() throws Exception {
        String userId = "user1";

        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", userId)
                        .header("X-Correlation-Id", "cid-test-mdc-001"))
                .andExpect(status().isOk());

        List<ILoggingEvent> logs = listAppender.list;
        assertThat(logs).isNotEmpty();

        // 1. Valida que o log do controller capturou @MDC("userId") do @PathVariable
        ILoggingEvent controllerLog = logs.stream()
                .filter(e -> e.getLoggerName().equals(UserOrchestratorController.class.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nenhum log do controller capturado"));

        assertThat(controllerLog.getMDCPropertyMap())
                .containsEntry("userId", userId)
                .containsEntry("flow", "GET /api/v1/orchestrator/users/{userId}")
                .containsEntry("correlation_id", "cid-test-mdc-001");

        // 2. Valida que o log do service herdou userId e adicionou @MDC(key = "flowType", value = "orchestrated-provisioning")
        ILoggingEvent serviceLog = logs.stream()
                .filter(e -> e.getLoggerName().equals(UserOrchestratorService.class.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nenhum log do service capturado"));

        assertThat(serviceLog.getMDCPropertyMap())
                .containsEntry("userId", userId)
                .containsEntry("flowType", "orchestrated-provisioning")
                .containsEntry("flow", "GET /api/v1/orchestrator/users/{userId}");

        // 3. Valida ausência total de contaminação residual de contexto (Zero Leakage)
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("flowType")).isNull();
        assertThat(MDC.get("flow")).isNull();
    }

    @Test
    @DisplayName("POST Flow: Deve extrair @MDC com SpEL de request e valores estáticos, garantindo limpeza")
    void shouldEnrichMdcViaSpelAndStaticValuesInPostFlow() throws Exception {
        UserRegistrationRequest request = new UserRegistrationRequest(
                "alice99",
                "Alice Wonder",
                "alice@example.com"
        );

        mockMvc.perform(post("/api/v1/orchestrator/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("X-Correlation-Id", "cid-post-mdc-999"))
                .andExpect(status().isAccepted());

        List<ILoggingEvent> logs = listAppender.list;
        assertThat(logs).isNotEmpty();

        // 1. Valida log do controller com SpEL (#request.userId) e valor estático (channel=web)
        ILoggingEvent controllerLog = logs.stream()
                .filter(e -> e.getLoggerName().equals(UserOrchestratorController.class.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Log do controller não encontrado"));

        assertThat(controllerLog.getMDCPropertyMap())
                .containsEntry("userId", "alice99")
                .containsEntry("channel", "web")
                .containsEntry("flow", "POST /api/v1/orchestrator/users")
                .containsEntry("correlation_id", "cid-post-mdc-999");

        // 2. Valida log do service com step e userId via SpEL
        ILoggingEvent serviceLog = logs.stream()
                .filter(e -> e.getLoggerName().equals(UserOrchestratorService.class.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Log do service não encontrado"));

        assertThat(serviceLog.getMDCPropertyMap())
                .containsEntry("userId", "alice99")
                .containsEntry("step", "initiate-async-registration")
                .containsEntry("step.type", "BUSINESS");

        // 3. Limpeza total de MDC na thread
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("channel")).isNull();
    }

    @Test
    @DisplayName("Centralized Logback: Deve verificar que as propriedades do logback.yml foram carregadas pelo EnvironmentPostProcessor")
    void shouldLoadCentralizedLogbackProperties() {
        String consolePattern = environment.getProperty("logging.pattern.console");
        assertThat(consolePattern).isNotBlank();
        assertThat(consolePattern).contains("correlation_id");
        assertThat(consolePattern).contains("flow");
    }
}
