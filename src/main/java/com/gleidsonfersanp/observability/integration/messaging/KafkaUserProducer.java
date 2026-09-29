package com.gleidsonfersanp.observability.integration.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaUserProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaUserProducer.class);
    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaUserProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishUserCreated(String userId) {
        String payload = "User created: " + userId;
        log.info("Publishing user created event to Kafka: {}", payload);
        kafkaTemplate.send("user-events", payload);
    }

    public void publishUserUpdated(String userId) {
        String payload = "User updated: " + userId;
        log.info("Publishing user updated event to Kafka: {}", payload);
        kafkaTemplate.send("user-events", payload);
    }
    
    public void publishUserRegistration(com.gleidsonfersanp.observability.domain.UserRegistrationRequest request) {
        String payload = "User registration: " + request.userId();
        log.info("Publishing user registration event to Kafka: {}", payload);
        kafkaTemplate.send("user-events", payload);
    }
}
