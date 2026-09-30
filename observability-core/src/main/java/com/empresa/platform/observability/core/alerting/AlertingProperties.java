package com.empresa.platform.observability.core.alerting;

import java.util.HashMap;
import java.util.Map;

public class AlertingProperties {

    private boolean enabled = true;
    private String webhookUrl;
    private long defaultStepSlaMs = 1500;
    private long defaultFlowSlaMs = 3000;
    private long kafkaLagThreshold = 100;
    private int sqsDepthThreshold = 50;
    private int hikariPendingThreshold = 1;

    private Map<String, Long> stepSlaMs = new HashMap<>();
    private Map<String, Long> flowSlaMs = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public long getDefaultStepSlaMs() {
        return defaultStepSlaMs;
    }

    public void setDefaultStepSlaMs(long defaultStepSlaMs) {
        this.defaultStepSlaMs = defaultStepSlaMs;
    }

    public long getDefaultFlowSlaMs() {
        return defaultFlowSlaMs;
    }

    public void setDefaultFlowSlaMs(long defaultFlowSlaMs) {
        this.defaultFlowSlaMs = defaultFlowSlaMs;
    }

    public long getKafkaLagThreshold() {
        return kafkaLagThreshold;
    }

    public void setKafkaLagThreshold(long kafkaLagThreshold) {
        this.kafkaLagThreshold = kafkaLagThreshold;
    }

    public int getSqsDepthThreshold() {
        return sqsDepthThreshold;
    }

    public void setSqsDepthThreshold(int sqsDepthThreshold) {
        this.sqsDepthThreshold = sqsDepthThreshold;
    }

    public int getHikariPendingThreshold() {
        return hikariPendingThreshold;
    }

    public void setHikariPendingThreshold(int hikariPendingThreshold) {
        this.hikariPendingThreshold = hikariPendingThreshold;
    }

    public Map<String, Long> getStepSlaMs() {
        return stepSlaMs;
    }

    public void setStepSlaMs(Map<String, Long> stepSlaMs) {
        this.stepSlaMs = stepSlaMs;
    }

    public Map<String, Long> getFlowSlaMs() {
        return flowSlaMs;
    }

    public void setFlowSlaMs(Map<String, Long> flowSlaMs) {
        this.flowSlaMs = flowSlaMs;
    }

    public long getThresholdForStep(String stepName) {
        return stepSlaMs.getOrDefault(stepName, defaultStepSlaMs);
    }

    public long getThresholdForFlow(String flowName) {
        return flowSlaMs.getOrDefault(flowName, defaultFlowSlaMs);
    }
}
