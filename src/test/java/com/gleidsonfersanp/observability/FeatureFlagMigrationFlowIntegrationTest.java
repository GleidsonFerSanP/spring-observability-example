package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.gleidsonfersanp.observability.feature.FeatureToggleService;
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
class FeatureFlagMigrationFlowIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private FeatureToggleService featureToggleService;

    private ListAppender<ILoggingEvent> logAppender;
    private final List<Observation.Context> finishedObservations = new CopyOnWriteArrayList<>();

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
        featureToggleService.clearOverrides();

        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.setContext(rootLogger.getLoggerContext());
        logAppender.start();
        rootLogger.addAppender(logAppender);

        finishedObservations.clear();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                finishedObservations.add(context);
            }
        });
    }

    @AfterEach
    void tearDown() {
        featureToggleService.clearOverrides();
        if (logAppender != null) {
            Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    @Test
    @DisplayName("Cenário 1: Rota Legada (toggle desabilitado) -> decompõe Customer HTTP + Billing HTTP + Notificação HTTP")
    void shouldExecuteLegacyRouteWhenFeatureToggleDisabled() throws Exception {
        featureToggleService.setFeatureOverride("user-provisioning-v2", false);

        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", equalTo("user1")))
                .andExpect(jsonPath("$.notificationStatus", equalTo("DELIVERED")));

        // 1. Asserção das métricas dimensionadas com variant=legacy
        Timer flowDurationLegacy = meterRegistry.get("observability.flow.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("variant", "legacy")
                .tag("status", "SUCCESS")
                .timer();
        assertThat(flowDurationLegacy).isNotNull();
        assertThat(flowDurationLegacy.count()).isGreaterThanOrEqualTo(1);

        Timer totalDurationLegacy = meterRegistry.get("flow_total_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("variant", "legacy")
                .timer();
        assertThat(totalDurationLegacy).isNotNull();

        // 2. Fatias e decomposição de latência na rota legada
        Timer customerAttributed = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("component", "API Customer (GET /customers/{userId})")
                .tag("variant", "legacy")
                .timer();
        assertThat(customerAttributed).isNotNull();

        Timer billingAttributed = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("component", "API Billing (GET /billing/accounts/{userId})")
                .tag("variant", "legacy")
                .timer();
        assertThat(billingAttributed).isNotNull();

        Timer notificationAttributed = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("component", "API Notificação (POST /notifications)")
                .tag("variant", "legacy")
                .timer();
        assertThat(notificationAttributed).isNotNull();

        // 3. Verificação do MDC nos logs capturados
        List<ILoggingEvent> logsWithVariant = logAppender.list.stream()
                .filter(event -> "legacy".equals(event.getMDCPropertyMap().get("variant")))
                .toList();
        assertThat(logsWithVariant).isNotEmpty();
        assertThat(logsWithVariant.get(0).getMDCPropertyMap())
                .containsEntry("variant", "legacy")
                .containsEntry("feature.name", "user-provisioning-v2");

        // 4. Verificação da Observation (Trace)
        Observation.Context flowObservation = finishedObservations.stream()
                .filter(ctx -> "flow.get.api.v1.orchestrator.users.userid".equals(ctx.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(flowObservation.getLowCardinalityKeyValues())
                .anyMatch(kv -> "variant".equals(kv.getKey()) && "legacy".equals(kv.getValue()))
                .anyMatch(kv -> "feature".equals(kv.getKey()) && "user-provisioning-v2".equals(kv.getValue()));
    }

    @Test
    @DisplayName("Cenário 2: Rota Nova (toggle habilitado) -> decompõe Cache Redis + Billing HTTP + Publicação SQS")
    void shouldExecuteNewRouteWhenFeatureToggleEnabled() throws Exception {
        featureToggleService.setFeatureOverride("user-provisioning-v2", true);

        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", equalTo("user2")))
                .andExpect(jsonPath("$.notificationStatus", equalTo("DELIVERED_ASYNC_SQS")));

        // 1. Asserção das métricas dimensionadas com variant=new
        Timer flowDurationNew = meterRegistry.get("observability.flow.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("variant", "new")
                .tag("status", "SUCCESS")
                .timer();
        assertThat(flowDurationNew).isNotNull();
        assertThat(flowDurationNew.count()).isGreaterThanOrEqualTo(1);

        Timer totalDurationNew = meterRegistry.get("flow_total_duration_seconds")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("variant", "new")
                .timer();
        assertThat(totalDurationNew).isNotNull();

        // 2. Fatias e decomposição de latência na nova rota (Redis + Billing + SQS)
        Timer cacheAttributed = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("component", "Cache Redis (GET customer:{userId})")
                .tag("variant", "new")
                .timer();
        assertThat(cacheAttributed).isNotNull();

        Timer billingAttributed = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("component", "API Billing (GET /billing/accounts/{userId})")
                .tag("variant", "new")
                .timer();
        assertThat(billingAttributed).isNotNull();

        Timer sqsAttributed = meterRegistry.get("observability.flow.component.attributed.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("component", "Publicação SQS (welcome-email-queue)")
                .tag("variant", "new")
                .timer();
        assertThat(sqsAttributed).isNotNull();

        // 3. Verificação do MDC nos logs capturados
        List<ILoggingEvent> logsWithVariant = logAppender.list.stream()
                .filter(event -> "new".equals(event.getMDCPropertyMap().get("variant")))
                .toList();
        assertThat(logsWithVariant).isNotEmpty();
        assertThat(logsWithVariant.get(0).getMDCPropertyMap())
                .containsEntry("variant", "new")
                .containsEntry("feature.name", "user-provisioning-v2");

        // 4. Verificação da Observation (Trace)
        Observation.Context flowObservation = finishedObservations.stream()
                .filter(ctx -> "flow.get.api.v1.orchestrator.users.userid".equals(ctx.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(flowObservation.getLowCardinalityKeyValues())
                .anyMatch(kv -> "variant".equals(kv.getKey()) && "new".equals(kv.getValue()))
                .anyMatch(kv -> "feature".equals(kv.getKey()) && "user-provisioning-v2".equals(kv.getValue()));
    }

    @Test
    @DisplayName("Cenário 3: Comparação Analítica A/B -> coexistência simultânea das variantes legacy e new no registry")
    void shouldCompareLegacyVsNewVariantsSimultaneouslyInMetricsRegistry() throws Exception {
        // Executa 2 chamadas na rota legada
        featureToggleService.setFeatureOverride("user-provisioning-v2", false);
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user1")).andExpect(status().isOk());

        // Executa 2 chamadas na nova rota
        featureToggleService.setFeatureOverride("user-provisioning-v2", true);
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user2")).andExpect(status().isOk());

        // Valida que ambas as dimensões coexistem no registry e segregam o tráfego
        Timer legacyTimer = meterRegistry.get("observability.flow.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("variant", "legacy")
                .timer();
        assertThat(legacyTimer.count()).isGreaterThanOrEqualTo(2);

        Timer newTimer = meterRegistry.get("observability.flow.duration")
                .tag("flow", "GET /api/v1/orchestrator/users/{userId}")
                .tag("variant", "new")
                .timer();
        assertThat(newTimer.count()).isGreaterThanOrEqualTo(2);

        // Prova que cada variante possui seus componentes instrumentados distintos
        assertThat(meterRegistry.find("observability.flow.component.attributed.duration")
                .tag("variant", "legacy")
                .tag("component", "API Customer (GET /customers/{userId})")
                .timer()).isNotNull();

        assertThat(meterRegistry.find("observability.flow.component.attributed.duration")
                .tag("variant", "new")
                .tag("component", "Cache Redis (GET customer:{userId})")
                .timer()).isNotNull();
    }
}
