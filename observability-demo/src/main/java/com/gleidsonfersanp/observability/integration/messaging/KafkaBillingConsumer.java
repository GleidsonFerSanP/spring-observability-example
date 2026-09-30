package com.gleidsonfersanp.observability.integration.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaBillingConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaBillingConsumer.class);

    @KafkaListener(topics = "billing-events", groupId = "user-orchestrator-group")
    public void consume(String payload) {
        log.info("Received billing event via Kafka: {}", payload);
    }
}
