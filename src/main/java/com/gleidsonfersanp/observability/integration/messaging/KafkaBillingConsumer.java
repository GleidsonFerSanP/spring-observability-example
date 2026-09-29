package com.gleidsonfersanp.observability.integration.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import io.micrometer.observation.annotation.Observed;
import com.gleidsonfersanp.observability.observability.ObservationTag;

@Component
public class KafkaBillingConsumer {
    private static final Logger log = LoggerFactory.getLogger(KafkaBillingConsumer.class);

    @Observed(name = "messaging.consume", contextualName = "kafka-billing-consumer")
    @ObservationTag(key = "messaging.system", expression = "'kafka'")
    @KafkaListener(topics = "billing-events-topic", groupId = "billing-group")
    public void consume(String message) {
        log.info("Received Kafka billing event: {}", message);
        try { Thread.sleep(80); } catch (InterruptedException e) {}
    }
}
