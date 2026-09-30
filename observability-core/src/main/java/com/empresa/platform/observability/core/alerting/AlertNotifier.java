package com.empresa.platform.observability.core.alerting;

/**
 * Interface extensível para envio de alertas para diferentes canais
 * (Logs estruturados, Webhooks, Slack, Teams, PagerDuty, Event Bus).
 */
public interface AlertNotifier {

    void sendAlert(AlertEvent alert);

    String getChannelName();
}
