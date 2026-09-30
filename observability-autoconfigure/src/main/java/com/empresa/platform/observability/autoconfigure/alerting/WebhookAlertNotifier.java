package com.empresa.platform.observability.autoconfigure.alerting;

import com.empresa.platform.observability.core.alerting.AlertEvent;
import com.empresa.platform.observability.core.alerting.AlertNotifier;
import com.empresa.platform.observability.core.alerting.AlertingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class WebhookAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertNotifier.class);
    private final AlertingProperties properties;
    private final RestTemplate restTemplate;

    public WebhookAlertNotifier(AlertingProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = (builder != null ? builder : new RestTemplateBuilder())
                .setConnectTimeout(Duration.ofMillis(1000))
                .setReadTimeout(Duration.ofMillis(2000))
                .build();
    }

    @Override
    public void sendAlert(AlertEvent alert) {
        String webhookUrl = properties.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> payload = Map.of(
                        "alertId", alert.alertId(),
                        "timestamp", alert.timestamp().toString(),
                        "type", alert.type().name(),
                        "severity", alert.severity().name(),
                        "target", alert.target(),
                        "source", alert.source(),
                        "currentValue", alert.currentValue(),
                        "thresholdValue", alert.thresholdValue(),
                        "message", alert.message(),
                        "metadata", alert.metadata()
                );

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
                restTemplate.postForEntity(webhookUrl, request, String.class);
                log.debug("Alert successfully sent to webhook: {}", alert.alertId());

            } catch (Exception e) {
                log.warn("Failed to dispatch alert to webhook {}: {}", webhookUrl, e.getMessage());
            }
        });
    }

    @Override
    public String getChannelName() {
        return "WEBHOOK";
    }
}
