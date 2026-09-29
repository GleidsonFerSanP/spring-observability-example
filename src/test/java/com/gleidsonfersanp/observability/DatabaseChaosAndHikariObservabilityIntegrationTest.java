package com.gleidsonfersanp.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gleidsonfersanp.observability.database.DatabaseChaosService;
import com.gleidsonfersanp.observability.database.TransactionEntity;
import com.gleidsonfersanp.observability.database.TransactionRepository;
import com.gleidsonfersanp.observability.observability.alerting.AlertDispatcher;
import com.gleidsonfersanp.observability.observability.alerting.AlertEvent;
import com.gleidsonfersanp.observability.observability.alerting.AlertSeverity;
import com.gleidsonfersanp.observability.observability.alerting.AlertType;
import com.gleidsonfersanp.observability.observability.alerting.HikariPoolAlertWatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatabaseChaosAndHikariObservabilityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AlertDispatcher alertDispatcher;

    @Autowired
    private HikariPoolAlertWatcher hikariPoolAlertWatcher;

    @Autowired
    private DatabaseChaosService databaseChaosService;

    @Autowired
    private TransactionRepository transactionRepository;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        alertDispatcher.clearAlerts();
        hikariPoolAlertWatcher.resetAlertCooldown();
        transactionRepository.deleteAll();

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
    @DisplayName("Cenário 1: CRUD de transações normais, validação de persistência no H2 e métricas do pool HikariCP")
    void testNormalTransactionCrudAndHikariMetrics() throws Exception {
        // 1. Criar transação via API
        mockMvc.perform(post("/api/db-chaos/normal")
                        .param("amount", "250.75")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.amount").value(250.75))
                .andExpect(jsonPath("$.description", notNullValue()));

        // 2. Consultar transações
        mockMvc.perform(get("/api/db-chaos/normal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].amount").value(250.75));

        // 3. Validar se foi salvo no H2 diretamente pelo repository
        List<TransactionEntity> saved = transactionRepository.findAll();
        assertThat(saved).isNotEmpty();
        assertThat(saved.get(0).getAmount()).isEqualTo(250.75);

        // 4. Validar logs gerados pela operação no banco
        List<String> logs = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logs).anyMatch(l -> l.contains("Creating normal transaction in database"));
        assertThat(logs).anyMatch(l -> l.contains("Fetching all transactions"));

        // 5. Validar existência das métricas de HikariCP registradas pelo Micrometer
        Search activeConns = meterRegistry.find("hikaricp.connections.active");
        assertThat(activeConns.gauge()).isNotNull();

        Search idleConns = meterRegistry.find("hikaricp.connections.idle");
        assertThat(idleConns.gauge()).isNotNull();

        Search maxConns = meterRegistry.find("hikaricp.connections.max");
        assertThat(maxConns.gauge()).isNotNull();
        assertThat(maxConns.gauge().value()).isEqualTo(5.0); // max-pool-size: 5
    }

    @Test
    @DisplayName("Cenário 2: Watchdog do HikariCP detecta starvation de conexões pendentes e dispara alerta CRITICAL")
    void testHikariPoolWatchdogDetectsPendingConnectionsStarvation() throws Exception {
        // Pool tem tamanho máximo de 5 conexões.
        // Lançamos 8 threads concorrentes segurando conexão por 1.5s.
        // As 3 threads excedentes ficarão aguardando no pool (pending >= 1).
        int numThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    databaseChaosService.simulatePoolExhaustion(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }
        startLatch.countDown();

        // Aguarda threads excedentes ficarem em estado pending
        for (int i = 0; i < 40; i++) {
            Thread.sleep(100);
            Search pendingSearch = meterRegistry.find("hikaricp.connections.pending");
            if (pendingSearch != null && pendingSearch.gauge() != null && pendingSearch.gauge().value() >= 1) {
                break;
            }
        }

        // Executa o watchdog para inspecionar o estado do pool sob pressão
        hikariPoolAlertWatcher.monitorHikariPool();

        // Aguarda finalização das tarefas para liberar o pool
        finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Validar se o alerta DATABASE_POOL_STARVATION foi disparado
        List<AlertEvent> alerts = alertDispatcher.getRecentAlerts();
        assertThat(alerts).anyMatch(a -> a.type() == AlertType.DATABASE_POOL_STARVATION
                && a.severity() == AlertSeverity.CRITICAL
                && a.source().equals("HikariPool"));

        // Validar se a métrica de alerta do Micrometer foi incrementada
        Counter alertCounter = meterRegistry.find("alerts_triggered_total")
                .tag("type", "DATABASE_POOL_STARVATION")
                .tag("severity", "CRITICAL")
                .counter();
        assertThat(alertCounter).isNotNull();
        assertThat(alertCounter.count()).isGreaterThanOrEqualTo(1.0);

        // Validar endpoint HTTP de alertas
        mockMvc.perform(get("/api/v1/orchestrator/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[?(@.type == 'DATABASE_POOL_STARVATION')].severity").value(org.hamcrest.Matchers.hasItem("CRITICAL")));
    }

    @Test
    @DisplayName("Cenário 3: Watchdog do HikariCP detecta delta de timeouts de conexão e gera alerta correspondente")
    void testHikariPoolWatchdogDetectsConnectionTimeoutsDelta() throws Exception {
        // Cria um counter simulado de timeout de conexão se não existir ou incrementa
        Counter timeoutCounter = meterRegistry.find("hikaricp.connections.timeout.total").counter();
        if (timeoutCounter == null) {
            timeoutCounter = Counter.builder("hikaricp.connections.timeout.total")
                    .tag("pool", "observability-hikari-pool")
                    .register(meterRegistry);
        }

        // Simula a ocorrência de 2 timeouts no pool HikariCP
        timeoutCounter.increment(2.0);

        // Invoca o watchdog
        hikariPoolAlertWatcher.monitorHikariPool();

        // Valida se o alerta de esgotamento/timeout foi emitido
        List<AlertEvent> alerts = alertDispatcher.getRecentAlerts();
        assertThat(alerts).anyMatch(a -> a.type() == AlertType.DATABASE_POOL_STARVATION
                && a.severity() == AlertSeverity.CRITICAL
                && a.message().contains("Esgotamento de pool detectado"));
    }
}
