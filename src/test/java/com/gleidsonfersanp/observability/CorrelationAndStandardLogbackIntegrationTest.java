package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.empresa.platform.observability.core.correlation.CorrelationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorrelationAndStandardLogbackIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void resetWireMockRequests() {
        if (wireMockServer != null) {
            wireMockServer.resetRequests();
        }
    }

    @Test
    @DisplayName("Propagação Inbound/Outbound: Deve receber X-Correlation-Id no header e propagar downstream via Feign")
    void shouldPropagateCorrelationIdFromHttpHeaderToDownstream() throws Exception {
        String testCorrelationId = "custom-cid-" + UUID.randomUUID();

        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user1")
                        .header("X-Correlation-Id", testCorrelationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", testCorrelationId));

        // Valida que o Feign Client propagou o cabeçalho X-Correlation-Id para o Customer e Billing WireMock
        wireMockServer.verify(getRequestedFor(urlMatching("/customers/.*"))
                .withHeader("X-Correlation-Id", equalTo(testCorrelationId)));
        wireMockServer.verify(getRequestedFor(urlMatching("/billing/accounts/.*"))
                .withHeader("X-Correlation-Id", equalTo(testCorrelationId)));

        // MDC deve estar limpo após a requisição
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Geração Automática: Deve gerar UUID para Correlation ID quando cliente não envia cabeçalho")
    void shouldGenerateCorrelationIdWhenHeaderNotProvided() throws Exception {
        mockMvc.perform(get("/api/v1/orchestrator/users/{userId}", "user1"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(result -> {
                    String generatedCid = result.getResponse().getHeader("X-Correlation-Id");
                    assertThat(generatedCid).isNotBlank();
                    wireMockServer.verify(getRequestedFor(urlMatching("/customers/.*"))
                            .withHeader("X-Correlation-Id", equalTo(generatedCid)));
                });

        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("CorrelationContext: Deve gerenciar o ciclo de vida do MDC e escopos aninhados com segurança")
    void shouldManageCorrelationContextLifecycle() {
        String cid1 = "cid-escopo-1";
        String cid2 = "cid-escopo-2";

        assertThat(CorrelationContext.getCorrelationId()).isNull();

        CorrelationContext.runWithCorrelationId(cid1, () -> {
            assertThat(CorrelationContext.getCorrelationId()).isEqualTo(cid1);
            assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isEqualTo(cid1);

            String result = CorrelationContext.supplyWithCorrelationId(cid2, () -> {
                assertThat(CorrelationContext.getCorrelationId()).isEqualTo(cid2);
                return "sucesso";
            });

            assertThat(result).isEqualTo("sucesso");
            assertThat(CorrelationContext.getCorrelationId()).isEqualTo(cid1);
        });

        assertThat(CorrelationContext.getCorrelationId()).isNull();
    }

    @Test
    @DisplayName("Logback JSON Padronizado: Deve formatar eventos em JSON válido contendo atributos de trace, correlation e legs")
    void shouldFormatLoggingEventsIntoStandardValidJson() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        
        // Padrão estruturado JSON padronizado do logback-spring.xml (perfil container/prod)
        String jsonPattern = "{\"timestamp\":\"%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX,UTC}\",\"app\":\"user-orchestrator\",\"level\":\"%p\",\"thread\":\"%t\",\"logger\":\"%logger\",\"correlation_id\":\"%X{correlation_id:-none}\",\"traceId\":\"%X{traceId:-}\",\"spanId\":\"%X{spanId:-}\",\"leg_number\":\"%X{leg_number:-}\",\"leg_parent\":\"%X{leg_parent:-}\",\"leg_type\":\"%X{leg_type:-}\",\"leg_phase\":\"%X{leg_phase:-}\",\"leg_target\":\"%X{leg_target:-}\",\"leg_duration_ms\":\"%X{leg_duration_ms:-}\",\"leg_status\":\"%X{leg_status:-}\",\"message\":\"%replace(%m){'[\\r\\n\\t]', ' '}\",\"exception\":\"%replace(%wEx){'[\\r\\n\\t]', ' '}\"}";
        layout.setPattern(jsonPattern);
        layout.start();

        LoggingEvent event = new LoggingEvent(
                "com.gleidsonfersanp.observability.api.UserOrchestratorController",
                context.getLogger("com.gleidsonfersanp.observability.api.UserOrchestratorController"),
                Level.INFO,
                "Iniciando orquestração com sucesso",
                null,
                null
        );

        event.setMDCPropertyMap(Map.of(
                "correlation_id", "cid-abc-123",
                "traceId", "trace-xyz-789",
                "spanId", "span-001",
                "leg_number", "1",
                "leg_type", "INBOUND",
                "leg_phase", "REQUEST",
                "leg_target", "user-orchestrator"
        ));

        String formattedJson = layout.doLayout(event);
        assertThat(formattedJson).isNotBlank();

        // Faz o parse do JSON emitido para validar conformidade estrutural
        JsonNode jsonNode = objectMapper.readTree(formattedJson);
        assertThat(jsonNode.get("app").asText()).isEqualTo("user-orchestrator");
        assertThat(jsonNode.get("level").asText()).isEqualTo("INFO");
        assertThat(jsonNode.get("correlation_id").asText()).isEqualTo("cid-abc-123");
        assertThat(jsonNode.get("traceId").asText()).isEqualTo("trace-xyz-789");
        assertThat(jsonNode.get("spanId").asText()).isEqualTo("span-001");
        assertThat(jsonNode.get("leg_number").asText()).isEqualTo("1");
        assertThat(jsonNode.get("leg_type").asText()).isEqualTo("INBOUND");
        assertThat(jsonNode.get("leg_phase").asText()).isEqualTo("REQUEST");
        assertThat(jsonNode.get("leg_target").asText()).isEqualTo("user-orchestrator");
        assertThat(jsonNode.get("message").asText()).isEqualTo("Iniciando orquestração com sucesso");
    }
}
