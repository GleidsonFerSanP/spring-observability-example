package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.empresa.platform.observability.autoconfigure.CorrelationIdFilter;
import com.empresa.platform.observability.autoconfigure.aspect.FlowTrackingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.LegLoggingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.SpelObservationAspect;
import com.empresa.platform.observability.autoconfigure.async.ObservabilityTaskDecorator;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "observability.enabled=false"
})
class StarterMasterDisabledIntegrationTest {

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
    @DisplayName("Starter Disabled: 1. Nenhum bean de observabilidade do starter deve existir no ApplicationContext")
    void shouldNotLoadAnyStarterBeansWhenDisabled() {
        assertThat(applicationContext.getBeansOfType(FlowTrackingAspect.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(LegLoggingAspect.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(SpelObservationAspect.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(CorrelationIdFilter.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ObservabilityTaskDecorator.class)).isEmpty();
        assertThat(applicationContext.containsBean("observabilityFeignRequestInterceptor")).isFalse();
    }

    @Test
    @DisplayName("Starter Disabled: 2. Endpoints de negócio continuam funcionando normalmente sem qualquer quebra ou regressão")
    void shouldExecuteBusinessFlowNormallyWithoutObservabilityOverhead() throws Exception {
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", equalTo("user1")))
                .andExpect(jsonPath("$.customer.name", equalTo("John Doe")))
                .andExpect(jsonPath("$.billing.plan", equalTo("Enterprise")))
                .andExpect(jsonPath("$.notificationStatus", equalTo("DELIVERED")))
                .andExpect(header().doesNotExist("X-Correlation-Id")); // CorrelationIdFilter desligado

        // Valida que o AUDIT_LEG_LOGGER não emitiu logs de auditoria
        boolean hasLegAuditLogs = logAppender.list.stream()
                .anyMatch(event -> "AUDIT_LEG_LOGGER".equals(event.getLoggerName()));
        assertThat(hasLegAuditLogs).isFalse();

        // Valida que o FlowTrackingAspect não registrou a métrica customizada flow_total_duration_seconds
        assertThat(meterRegistry.find("flow_total_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .timer()).isNull();
    }
}
