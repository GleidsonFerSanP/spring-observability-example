package com.empresa.platform.observability.autoconfigure.binder;

import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertEvent;
import com.empresa.platform.observability.core.alerting.AlertSeverity;
import com.empresa.platform.observability.core.alerting.AlertType;
import com.empresa.platform.observability.core.alerting.AlertingProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Optional fallback collector for Kafka broker consumer group lag.
 * Disabled by default in production per Spec Section 45.
 */
public class KafkaLagMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagMetricsBinder.class);
    private final KafkaAdmin kafkaAdmin;
    private final AlertDispatcher alertDispatcher;
    private final AlertingProperties alertingProperties;
    private final Map<String, AtomicLong> lagGauges = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAlertTimestamps = new ConcurrentHashMap<>();
    private MeterRegistry meterRegistry;

    public KafkaLagMetricsBinder(KafkaAdmin kafkaAdmin, AlertDispatcher alertDispatcher, AlertingProperties alertingProperties) {
        this.kafkaAdmin = kafkaAdmin;
        this.alertDispatcher = alertDispatcher;
        this.alertingProperties = alertingProperties;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.meterRegistry = registry;
        try {
            AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kafka-lag-metrics-poller");
                t.setDaemon(true);
                return t;
            }).scheduleAtFixedRate(() -> pollLag(adminClient), 3, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Could not initialize Kafka AdminClient for lag monitoring: {}", e.getMessage());
        }
    }

    private void pollLag(AdminClient adminClient) {
        try {
            for (String group : new String[]{"user-orchestrator-group", "billing-group"}) {
                Map<TopicPartition, OffsetAndMetadata> groupOffsets =
                        adminClient.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS);

                if (groupOffsets.isEmpty()) {
                    continue;
                }

                Map<TopicPartition, OffsetSpec> requestOffsets = groupOffsets.keySet().stream()
                        .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                        adminClient.listOffsets(requestOffsets).all().get(3, TimeUnit.SECONDS);

                for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : groupOffsets.entrySet()) {
                    TopicPartition tp = entry.getKey();
                    long current = entry.getValue().offset();
                    ListOffsetsResult.ListOffsetsResultInfo endInfo = endOffsets.get(tp);

                    if (endInfo != null) {
                        long lag = Math.max(0, endInfo.offset() - current);
                        String key = tp.topic() + "-" + group;

                        lagGauges.computeIfAbsent(key, k -> {
                            AtomicLong gaugeVal = new AtomicLong(lag);
                            Gauge.builder("kafka_consumer_lag_records", gaugeVal, AtomicLong::get)
                                    .tag("topic", tp.topic())
                                    .tag("group", group)
                                    .description("Actual Kafka broker consumer group lag")
                                    .register(meterRegistry);
                            return gaugeVal;
                        }).set(lag);

                        if (alertingProperties != null && alertDispatcher != null) {
                            long threshold = alertingProperties.getKafkaLagThreshold();
                            if (lag > threshold) {
                                long now = System.currentTimeMillis();
                                Long lastAlert = lastAlertTimestamps.get(key);
                                if (lastAlert == null || (now - lastAlert > 30_000)) {
                                    lastAlertTimestamps.put(key, now);
                                    alertDispatcher.dispatch(AlertEvent.of(
                                            AlertType.KAFKA_LAG_HIGH,
                                            AlertSeverity.WARNING,
                                            "KafkaLagBinder",
                                            key,
                                            String.format("Lag no tópico '%s' (grupo '%s') atingiu %d mensagens (limiar: %d).",
                                                    tp.topic(), group, lag, threshold),
                                            lag,
                                            threshold,
                                            Map.of("topic", tp.topic(), "group", group, "lag", lag, "threshold", threshold)
                                    ));
                                }
                            } else {
                                lastAlertTimestamps.remove(key);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not poll Kafka consumer lag: {}", e.getMessage());
        }
    }
}
