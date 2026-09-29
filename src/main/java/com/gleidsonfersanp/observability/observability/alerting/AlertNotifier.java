package com.gleidsonfersanp.observability.observability.alerting;

/**
 * Interface extensível para envio de alertas para diferentes canais
 * (Logs estruturados, Webhooks, Slack, Teams, PagerDuty, Event Bus).
 */
public interface AlertNotifier {

    /**
     * Envia o alerta para o canal implementado.
     *
     * @param alert O evento de alerta com detalhes do incidente.
     */
    void sendAlert(AlertEvent alert);

    /**
     * Nome identificador do canal de alerta.
     */
    String getChannelName();
}
