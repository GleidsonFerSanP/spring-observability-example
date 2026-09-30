package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.empresa.platform.observability.core.annotation.LegType;
import com.empresa.platform.observability.core.annotation.LogLeg;
import com.empresa.platform.observability.core.annotation.MaskField;
import com.empresa.platform.observability.core.annotation.MaskPattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        UserOrchestratorApplication.class,
        LegCategoriesIntegrationTest.TestLegConfig.class
})
@ActiveProfiles("test")
class LegCategoriesIntegrationTest {

    @TestConfiguration
    static class TestLegConfig {
        @Bean
        public ConfigManagementService configManagementService() {
            return new ConfigManagementService();
        }

        @Bean
        public DatabaseAuditService databaseAuditService() {
            return new DatabaseAuditService();
        }

        @Bean
        public MessagingAuditService messagingAuditService() {
            return new MessagingAuditService();
        }

        @Bean
        public CacheAuditService cacheAuditService() {
            return new CacheAuditService();
        }

        @Bean
        public InternalProcessService internalProcessService() {
            return new InternalProcessService();
        }

        @Bean
        public DefaultFallbackClientService defaultFallbackClientService() {
            return new DefaultFallbackClientService();
        }
    }

    public static class ConfigManagementService {
        private final AtomicReference<String> capturedLegType = new AtomicReference<>();
        private final AtomicReference<String> capturedLegTarget = new AtomicReference<>();

        @LogLeg(
            target = "feature-flags-service",
            type = LegType.CONFIG,
            includePayload = true,
            mask = { @MaskField(expression = "secretKey", pattern = MaskPattern.PASSWORD) }
        )
        public ConfigResult loadConfig(String key, String secretKey) {
            capturedLegType.set(MDC.get("leg_type"));
            capturedLegTarget.set(MDC.get("leg_target"));
            return new ConfigResult(key, "v2-active", secretKey);
        }

        public String getCapturedLegType() {
            return capturedLegType.get();
        }

        public String getCapturedLegTarget() {
            return capturedLegTarget.get();
        }
    }

    public static class DatabaseAuditService {
        private final AtomicReference<String> capturedLegType = new AtomicReference<>();

        @LogLeg(target = "postgres-orders", type = LegType.DATABASE)
        public String executeQuery(String query) {
            capturedLegType.set(MDC.get("leg_type"));
            return "ROWS_AFFECTED_1";
        }

        public String getCapturedLegType() {
            return capturedLegType.get();
        }
    }

    public static class MessagingAuditService {
        private final AtomicReference<String> capturedLegType = new AtomicReference<>();

        @LogLeg(target = "kafka-notifications-topic", type = LegType.MESSAGING)
        public void publishEvent(String eventId) {
            capturedLegType.set(MDC.get("leg_type"));
        }

        public String getCapturedLegType() {
            return capturedLegType.get();
        }
    }

    public static class CacheAuditService {
        private final AtomicReference<String> capturedLegType = new AtomicReference<>();

        @LogLeg(target = "redis-session-cache", type = LegType.CACHE)
        public String getCachedSession(String sessionId) {
            capturedLegType.set(MDC.get("leg_type"));
            return "SESSION_ACTIVE";
        }

        public String getCapturedLegType() {
            return capturedLegType.get();
        }
    }

    public static class InternalProcessService {
        private final AtomicReference<String> capturedLegType = new AtomicReference<>();

        @LogLeg(target = "score-calculator", type = LegType.INTERNAL)
        public int computeScore(int base) {
            capturedLegType.set(MDC.get("leg_type"));
            return base * 10;
        }

        public String getCapturedLegType() {
            return capturedLegType.get();
        }
    }

    public static class DefaultFallbackClientService {
        private final AtomicReference<String> capturedLegTarget = new AtomicReference<>();
        private final AtomicReference<String> capturedLegType = new AtomicReference<>();

        @LogLeg // target = "", defaults to class simple name, type defaults to OUTBOUND
        public String executeCall() {
            capturedLegTarget.set(MDC.get("leg_target"));
            capturedLegType.set(MDC.get("leg_type"));
            return "OK";
        }

        public String getCapturedLegTarget() {
            return capturedLegTarget.get();
        }

        public String getCapturedLegType() {
            return capturedLegType.get();
        }
    }

    public record ConfigResult(String key, String status, String secretKey) {}

    @Autowired
    private ConfigManagementService configService;

    @Autowired
    private DatabaseAuditService databaseService;

    @Autowired
    private MessagingAuditService messagingService;

    @Autowired
    private CacheAuditService cacheService;

    @Autowired
    private InternalProcessService internalService;

    @Autowired
    private DefaultFallbackClientService fallbackService;

    private ListAppender<ILoggingEvent> auditListAppender;
    private Logger auditLegLogger;

    @BeforeEach
    void setUp() {
        MDC.clear();
        auditLegLogger = (Logger) LoggerFactory.getLogger("AUDIT_LEG_LOGGER");
        auditListAppender = new ListAppender<>();
        auditListAppender.start();
        auditLegLogger.addAppender(auditListAppender);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (auditLegLogger != null && auditListAppender != null) {
            auditLegLogger.detachAppender(auditListAppender);
        }
    }

    @Test
    @DisplayName("Deve registrar perna LegType.CONFIG no MDC e no AUDIT_LEG_LOGGER com mascaramento")
    void testConfigLegAuditAndMdc() {
        ConfigResult result = configService.loadConfig("checkout-v2", "super-secret-123");

        assertThat(result.status()).isEqualTo("v2-active");
        assertThat(configService.getCapturedLegType()).isEqualTo("CONFIG");
        assertThat(configService.getCapturedLegTarget()).isEqualTo("feature-flags-service");

        // MDC limpo no finally
        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();

        // Verifica eventos emitidos para o AUDIT_LEG_LOGGER
        List<ILoggingEvent> events = auditListAppender.list;
        assertThat(events).isNotEmpty();
        assertThat(events).anyMatch(e -> e.getFormattedMessage().contains("[LEG") &&
                e.getFormattedMessage().contains("[CONFIG]") &&
                e.getFormattedMessage().contains("feature-flags-service") &&
                e.getFormattedMessage().contains("********"));
    }

    @Test
    @DisplayName("Deve registrar perna LegType.DATABASE no MDC e no AUDIT_LEG_LOGGER")
    void testDatabaseLegAuditAndMdc() {
        String result = databaseService.executeQuery("SELECT * FROM orders");

        assertThat(result).isEqualTo("ROWS_AFFECTED_1");
        assertThat(databaseService.getCapturedLegType()).isEqualTo("DATABASE");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();

        List<ILoggingEvent> events = auditListAppender.list;
        assertThat(events).anyMatch(e -> e.getFormattedMessage().contains("[DATABASE]") &&
                e.getFormattedMessage().contains("postgres-orders"));
    }

    @Test
    @DisplayName("Deve registrar perna LegType.MESSAGING no MDC e no AUDIT_LEG_LOGGER")
    void testMessagingLegAuditAndMdc() {
        messagingService.publishEvent("evt-889");

        assertThat(messagingService.getCapturedLegType()).isEqualTo("MESSAGING");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();

        List<ILoggingEvent> events = auditListAppender.list;
        assertThat(events).anyMatch(e -> e.getFormattedMessage().contains("[MESSAGING]") &&
                e.getFormattedMessage().contains("kafka-notifications-topic"));
    }

    @Test
    @DisplayName("Deve registrar perna LegType.CACHE no MDC e no AUDIT_LEG_LOGGER")
    void testCacheLegAuditAndMdc() {
        String session = cacheService.getCachedSession("sess_abc123");

        assertThat(session).isEqualTo("SESSION_ACTIVE");
        assertThat(cacheService.getCapturedLegType()).isEqualTo("CACHE");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();

        List<ILoggingEvent> events = auditListAppender.list;
        assertThat(events).anyMatch(e -> e.getFormattedMessage().contains("[CACHE]") &&
                e.getFormattedMessage().contains("redis-session-cache"));
    }

    @Test
    @DisplayName("Deve registrar perna LegType.INTERNAL no MDC e no AUDIT_LEG_LOGGER")
    void testInternalLegAuditAndMdc() {
        int score = internalService.computeScore(7);

        assertThat(score).isEqualTo(70);
        assertThat(internalService.getCapturedLegType()).isEqualTo("INTERNAL");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();

        List<ILoggingEvent> events = auditListAppender.list;
        assertThat(events).anyMatch(e -> e.getFormattedMessage().contains("[INTERNAL]") &&
                e.getFormattedMessage().contains("score-calculator"));
    }

    @Test
    @DisplayName("Deve inferir o target da perna automaticamente quando omitido (fallback dinâmico)")
    void testDynamicFallbackTargetResolution() {
        String res = fallbackService.executeCall();

        assertThat(res).isEqualTo("OK");
        assertThat(fallbackService.getCapturedLegTarget()).isEqualTo("DefaultFallbackClientService");
        assertThat(fallbackService.getCapturedLegType()).isEqualTo("OUTBOUND");

        assertThat(MDC.get("leg_target")).isNull();
        assertThat(MDC.get("leg_type")).isNull();

        List<ILoggingEvent> events = auditListAppender.list;
        assertThat(events).anyMatch(e -> e.getFormattedMessage().contains("DefaultFallbackClientService") &&
                e.getFormattedMessage().contains("[OUTBOUND]"));
    }
}
