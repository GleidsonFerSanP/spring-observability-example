package com.gleidsonfersanp.observability.integration.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gleidsonfersanp.observability.domain.UserRegistrationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import io.micrometer.observation.annotation.Observed;
import com.gleidsonfersanp.observability.observability.ObservationTag;
import com.gleidsonfersanp.observability.observability.flow.TrackStep;

@Component
public class KafkaUserProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaUserProducer.class);
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaUserProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Observed(name = "messaging.produce", contextualName = "kafka-registration-produce")
    @ObservationTag(key = "messaging.system", expression = "'kafka'")
    @ObservationTag(key = "topic", expression = "'user-registration-topic'")
    @TrackStep("Publicação Kafka (user-registration-topic)")
    public void publishUserRegistration(UserRegistrationRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request);
            log.info("Publishing user registration event to Kafka: {}", payload);
            kafkaTemplate.send("user-registration-topic", payload);
        } catch (Exception e) {
            log.error("Error serializing request", e);
        }
    }

    @Observed(name = "messaging.produce", contextualName = "kafka-billing-produce")
    @ObservationTag(key = "messaging.system", expression = "'kafka'")
    @ObservationTag(key = "topic", expression = "'billing-events-topic'")
    @TrackStep("Publicação Kafka (billing-events-topic)")
    public void publishBillingEvent(String userId, String plan) {
        log.info("Publishing billing event to Kafka: {} - {}", userId, plan);
        kafkaTemplate.send("billing-events-topic", userId + "|" + plan);
    }
}
