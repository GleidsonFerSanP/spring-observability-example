package com.gleidsonfersanp.observability.observability.alerting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger("com.gleidsonfersanp.observability.alerting");

    @Override
    public void sendAlert(AlertEvent alert) {
        String logMessage = String.format(
                "[ALERT-%s] [%s] Target: '%s' | Source: '%s' | Value: %.2f (Threshold: %.2f) | Message: %s | Metadata: %s",
                alert.severity(),
                alert.type(),
                alert.target(),
                alert.source(),
                alert.currentValue(),
                alert.thresholdValue(),
                alert.message(),
                alert.metadata()
        );

        switch (alert.severity()) {
            case CRITICAL -> log.error("🚨 {}", logMessage);
            case WARNING -> log.warn("⚠️ {}", logMessage);
            case INFO -> log.info("ℹ️ {}", logMessage);
        }
    }

    @Override
    public String getChannelName() {
        return "LOG";
    }
}
