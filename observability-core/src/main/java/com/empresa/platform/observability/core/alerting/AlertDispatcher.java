package com.empresa.platform.observability.core.alerting;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);
    private static final int MAX_ALERT_HISTORY = 100;

    private final AlertingProperties properties;
    private final List<AlertNotifier> notifiers;
    private final MeterRegistry meterRegistry;
    private final List<AlertEvent> recentAlerts = Collections.synchronizedList(new LinkedList<>());

    public AlertDispatcher(AlertingProperties properties, List<AlertNotifier> notifiers, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.notifiers = notifiers != null ? notifiers : List.of();
        this.meterRegistry = meterRegistry;
    }

    public void dispatch(AlertEvent alert) {
        if (!properties.isEnabled()) {
            return;
        }

        if (meterRegistry != null) {
            Counter.builder("alerts_triggered_total")
                    .tag("type", alert.type().name())
                    .tag("severity", alert.severity().name())
                    .tag("target", alert.target())
                    .description("Total de alertas e incidentes disparados pelo sistema")
                    .register(meterRegistry)
                    .increment();
        }

        recentAlerts.add(0, alert);
        while (recentAlerts.size() > MAX_ALERT_HISTORY) {
            recentAlerts.remove(recentAlerts.size() - 1);
        }

        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.sendAlert(alert);
            } catch (Exception e) {
                log.error("Erro ao enviar alerta via canal {}: {}", notifier.getChannelName(), e.getMessage());
            }
        }
    }

    public List<AlertEvent> getRecentAlerts() {
        return Collections.unmodifiableList(new LinkedList<>(recentAlerts));
    }

    public void clearAlerts() {
        recentAlerts.clear();
    }
}
