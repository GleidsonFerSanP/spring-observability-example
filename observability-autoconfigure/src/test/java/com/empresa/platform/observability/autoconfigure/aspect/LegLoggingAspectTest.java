package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.annotation.LegType;
import com.empresa.platform.observability.core.annotation.LogLeg;
import com.empresa.platform.observability.core.annotation.MaskField;
import com.empresa.platform.observability.core.annotation.MaskPattern;
import com.empresa.platform.observability.core.leg.LegContext;
import com.empresa.platform.observability.core.leg.SpelMaskingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegLoggingAspectTest {

    private LegLoggingAspect aspect;
    private ObjectMapper objectMapper;

    interface SampleIntegrationClient {
        @LogLeg
        String executeDefaultFallback();
    }

    static class SampleIntegrationClientImpl implements SampleIntegrationClient {
        private final AtomicReference<String> capturedTarget = new AtomicReference<>();
        private final AtomicReference<String> capturedType = new AtomicReference<>();

        @Override
        @LogLeg
        public String executeDefaultFallback() {
            capturedTarget.set(MDC.get("leg_target"));
            capturedType.set(MDC.get("leg_type"));
            return "SUCCESS";
        }
    }

    static class SampleService {
        private final AtomicReference<String> capturedTarget = new AtomicReference<>();
        private final AtomicReference<String> capturedType = new AtomicReference<>();
        private final AtomicReference<String> capturedLegNumber = new AtomicReference<>();
        private final AtomicReference<String> capturedPhase = new AtomicReference<>();

        @LogLeg(
            target = "vault-secrets",
            type = LegType.CONFIG,
            includePayload = true,
            mask = { @MaskField(expression = "token", pattern = MaskPattern.PASSWORD) }
        )
        public SecretData loadConfigSecret(String token) {
            captureMdc();
            return new SecretData(token, "active");
        }

        @LogLeg(target = "postgres-orders", type = LegType.DATABASE)
        public String queryDatabase(String orderId) {
            captureMdc();
            return "Order-" + orderId;
        }

        @LogLeg(target = "kafka-events-topic", type = LegType.MESSAGING)
        public void publishMessage(String payload) {
            captureMdc();
        }

        @LogLeg(target = "redis-session-cache", type = LegType.CACHE)
        public String lookupCache(String key) {
            captureMdc();
            return "Cached-" + key;
        }

        @LogLeg(target = "risk-calculator-engine", type = LegType.INTERNAL)
        public int calculateInternalScore(int amount) {
            captureMdc();
            return amount * 2;
        }

        @LogLeg(target = "failing-service", type = LegType.CONFIG)
        public void failingOperation() {
            captureMdc();
            throw new IllegalStateException("Service unavailable");
        }

        private void captureMdc() {
            capturedTarget.set(MDC.get("leg_target"));
            capturedType.set(MDC.get("leg_type"));
            capturedLegNumber.set(MDC.get("leg_number"));
            capturedPhase.set(MDC.get("leg_phase"));
        }
    }

    record SecretData(String token, String status) {}

    @BeforeEach
    void setUp() {
        MDC.clear();
        objectMapper = new ObjectMapper();
        SpelMaskingService maskingService = new SpelMaskingService(objectMapper);
        aspect = new LegLoggingAspect(maskingService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        LegContext.clear();
    }

    private SampleService createProxy(SampleService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private SampleIntegrationClient createInterfaceProxy(SampleIntegrationClient target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setInterfaces(SampleIntegrationClient.class);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    @DisplayName("Deve auditar perna do tipo CONFIG com injeção em leg_type e leg_target no MDC e limpeza no finally")
    void shouldTrackConfigLegWithPayloadAndMasking() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        SecretData result = proxy.loadConfigSecret("super-secret-token");

        assertThat(result.token()).isEqualTo("super-secret-token");
        assertThat(service.capturedTarget.get()).isEqualTo("vault-secrets");
        assertThat(service.capturedType.get()).isEqualTo("CONFIG");
        assertThat(service.capturedLegNumber.get()).isEqualTo("1");
        assertThat(service.capturedPhase.get()).isEqualTo("REQUEST");

        // Verifica que o MDC foi completamente limpo no finally
        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();
        assertThat(MDC.get("leg_number")).isNull();
    }

    @Test
    @DisplayName("Deve auditar perna do tipo DATABASE")
    void shouldTrackDatabaseLeg() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        String result = proxy.queryDatabase("ord-123");

        assertThat(result).isEqualTo("Order-ord-123");
        assertThat(service.capturedTarget.get()).isEqualTo("postgres-orders");
        assertThat(service.capturedType.get()).isEqualTo("DATABASE");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();
    }

    @Test
    @DisplayName("Deve auditar perna do tipo MESSAGING")
    void shouldTrackMessagingLeg() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        proxy.publishMessage("event-payload");

        assertThat(service.capturedTarget.get()).isEqualTo("kafka-events-topic");
        assertThat(service.capturedType.get()).isEqualTo("MESSAGING");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();
    }

    @Test
    @DisplayName("Deve auditar perna do tipo CACHE")
    void shouldTrackCacheLeg() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        String cached = proxy.lookupCache("session-999");

        assertThat(cached).isEqualTo("Cached-session-999");
        assertThat(service.capturedTarget.get()).isEqualTo("redis-session-cache");
        assertThat(service.capturedType.get()).isEqualTo("CACHE");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();
    }

    @Test
    @DisplayName("Deve auditar perna do tipo INTERNAL")
    void shouldTrackInternalLeg() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        int score = proxy.calculateInternalScore(50);

        assertThat(score).isEqualTo(100);
        assertThat(service.capturedTarget.get()).isEqualTo("risk-calculator-engine");
        assertThat(service.capturedType.get()).isEqualTo("INTERNAL");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();
    }

    @Test
    @DisplayName("Deve realizar fallback inteligente do target para o nome da interface declaradora quando target for vazio")
    void shouldFallbackTargetToDeclaringInterfaceName() {
        SampleIntegrationClientImpl client = new SampleIntegrationClientImpl();
        SampleIntegrationClient proxy = createInterfaceProxy(client);

        String result = proxy.executeDefaultFallback();

        assertThat(result).isEqualTo("SUCCESS");
        // O fallback deve inferir "SampleIntegrationClient" da interface, não "$Proxy..."
        assertThat(client.capturedTarget.get()).isEqualTo("SampleIntegrationClient");
        assertThat(client.capturedType.get()).isEqualTo("OUTBOUND");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();
    }

    @Test
    @DisplayName("Deve garantir limpeza estrita do MDC mesmo quando a perna lançar exceção")
    void shouldCleanupMdcOnException() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        assertThatThrownBy(proxy::failingOperation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Service unavailable");

        assertThat(service.capturedTarget.get()).isEqualTo("failing-service");
        assertThat(service.capturedType.get()).isEqualTo("CONFIG");

        // MDC deve estar limpo mesmo após exceção
        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();
        assertThat(MDC.get("leg_number")).isNull();
    }
}
