package com.empresa.platform.observability.autoconfigure.jdbc;

import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertEvent;
import com.empresa.platform.observability.core.alerting.AlertSeverity;
import com.empresa.platform.observability.core.alerting.AlertType;
import com.empresa.platform.observability.core.alerting.AlertingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class HikariPoolAlertWatcher {

    private static final Logger log = LoggerFactory.getLogger(HikariPoolAlertWatcher.class);

    private final MeterRegistry meterRegistry;
    private final AlertDispatcher alertDispatcher;
    private final AlertingProperties properties;
    private final AtomicLong lastAlertTimestamp = new AtomicLong(0);
    private double lastTimeoutCount = 0;

    public HikariPoolAlertWatcher(MeterRegistry meterRegistry, AlertDispatcher alertDispatcher, AlertingProperties properties) {
        this.meterRegistry = meterRegistry;
        this.alertDispatcher = alertDispatcher;
        this.properties = properties != null ? properties : new AlertingProperties();
    }

    @Scheduled(fixedDelay = 5000)
    public void monitorHikariPool() {
        if (!properties.isEnabled() || meterRegistry == null || alertDispatcher == null) {
            return;
        }

        try {
            Search pendingSearch = meterRegistry.find("hikaricp.connections.pending");
            if (pendingSearch.gauge() != null) {
                double pending = pendingSearch.gauge().value();
                int threshold = properties.getHikariPendingThreshold();

                if (pending >= threshold) {
                    long now = System.currentTimeMillis();
                    if (now - lastAlertTimestamp.get() > 30_000) {
                        lastAlertTimestamp.set(now);
                        alertDispatcher.dispatch(AlertEvent.of(
                                AlertType.DATABASE_POOL_STARVATION,
                                AlertSeverity.CRITICAL,
                                "HikariPool",
                                "observability-hikari-pool",
                                String.format("Pool de conexões sob estresse crítico: %.0f threads aguardando na fila (limiar: %d).",
                                        pending, threshold),
                                pending,
                                threshold,
                                Map.of("pendingThreads", pending, "threshold", threshold)
                        ));
                    }
                }
            }

            Search timeoutSearch = meterRegistry.find("hikaricp.connections.timeout.total");
            if (timeoutSearch.counter() != null) {
                double currentTimeoutCount = timeoutSearch.counter().count();
                if (currentTimeoutCount > lastTimeoutCount) {
                    double delta = currentTimeoutCount - lastTimeoutCount;
                    lastTimeoutCount = currentTimeoutCount;

                    alertDispatcher.dispatch(AlertEvent.of(
                            AlertType.DATABASE_POOL_STARVATION,
                            AlertSeverity.CRITICAL,
                            "HikariPool",
                            "observability-hikari-pool",
                            String.format("Esgotamento de pool detectado! Ocorreram %.0f novos timeouts ao obter conexão com o banco.", delta),
                            delta,
                            0.0,
                            Map.of("totalTimeouts", currentTimeoutCount, "delta", delta)
                    ));
                }
            }

        } catch (Exception e) {
            log.trace("Não foi possível inspecionar métricas do HikariCP: {}", e.getMessage());
        }
    }

    public void resetAlertCooldown() {
        this.lastAlertTimestamp.set(0);
        this.lastTimeoutCount = 0;
    }
}
