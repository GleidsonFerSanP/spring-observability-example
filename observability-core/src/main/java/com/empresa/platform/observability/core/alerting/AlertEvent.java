package com.empresa.platform.observability.core.alerting;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AlertEvent(
        String alertId,
        Instant timestamp,
        AlertType type,
        AlertSeverity severity,
        String source,
        String target,
        String message,
        double currentValue,
        double thresholdValue,
        Map<String, Object> metadata
) {
    public static AlertEvent of(
            AlertType type,
            AlertSeverity severity,
            String source,
            String target,
            String message,
            double currentValue,
            double thresholdValue,
            Map<String, Object> metadata
    ) {
        return new AlertEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                type,
                severity,
                source,
                target,
                message,
                currentValue,
                thresholdValue,
                metadata != null ? metadata : Map.of()
        );
    }
}
