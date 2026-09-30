package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.empresa.platform.observability.autoconfigure.aspect.FlowTrackingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.LegLoggingAspect;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "observability.leg-logging.enabled=false",
        "observability.feign.enabled=false"
})
class StarterGranularDisablingIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

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
        if (wireMockServer != null) {
            wireMockServer.resetRequests();
        }
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.setContext(rootLogger.getLoggerContext());
        logAppender.start();
        rootLogger.addAppender(logAppender);
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
    @DisplayName("Granular Toggles: LegLogging e Feign desabilitados enquanto FlowTracking continua ativo")
    void shouldSelectivelyDisableLegsAndFeignWhileKeepingFlowMetrics() throws Exception {
        // 1. Valida presença e ausência seletiva de beans no contexto
        assertThat(applicationContext.getBeansOfType(FlowTrackingAspect.class)).isNotEmpty();
        assertThat(applicationContext.getBeansOfType(LegLoggingAspect.class)).isEmpty();
        assertThat(applicationContext.containsBean("observabilityFeignRequestInterceptor")).isFalse();

        // 2. Executa requisição no entrypoint
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", equalTo("user1")))
                .andExpect(jsonPath("$.customer.name", equalTo("John Doe")));

        // 3. Valida que o FlowTrackingAspect gravou a métrica de fluxo com sucesso
        Timer flowTimer = meterRegistry.find("flow_total_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .timer();
        assertThat(flowTimer).isNotNull();
        assertThat(flowTimer.count()).isGreaterThanOrEqualTo(1);

        // 4. Valida que o LegLoggingAspect NÃO emitiu logs de auditoria
        boolean hasLegAuditLogs = logAppender.list.stream()
                .anyMatch(event -> "AUDIT_LEG_LOGGER".equals(event.getLoggerName()));
        assertThat(hasLegAuditLogs).isFalse();

        // 5. Valida que o Feign Interceptor NÃO adicionou o header x-correlation-id na saída
        wireMockServer.verify(getRequestedFor(urlMatching("/customers/.*"))
                .withHeader("x-correlation-id", absent()));
    }
}
