package com.gleidsonfersanp.observability.observability;

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
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class KafkaLagMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagMetricsBinder.class);
    private final KafkaAdmin kafkaAdmin;
    private final Map<String, AtomicLong> lagGauges = new ConcurrentHashMap<>();
    private MeterRegistry meterRegistry;

    public KafkaLagMetricsBinder(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.meterRegistry = registry;
        AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());

        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kafka-lag-metrics-poller");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> pollLag(adminClient), 3, 5, TimeUnit.SECONDS);
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
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not poll Kafka consumer lag: {}", e.getMessage());
        }
    }
}
