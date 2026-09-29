package com.gleidsonfersanp.observability.integration.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gleidsonfersanp.observability.application.UserOrchestratorService;
import com.gleidsonfersanp.observability.domain.UserProfile;
import com.gleidsonfersanp.observability.domain.UserRegistrationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import io.micrometer.observation.annotation.Observed;
import com.gleidsonfersanp.observability.observability.ObservationTag;
import com.gleidsonfersanp.observability.observability.flow.TrackFlow;

@Component
public class KafkaUserConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaUserConsumer.class);
    private final UserOrchestratorService service;
    private final SqsUserProducer sqsProducer;
    private final ObjectMapper objectMapper;
    private final KafkaUserProducer kafkaProducer;

    @org.springframework.beans.factory.annotation.Value("${app.kafka.user-consumer.delay-ms:120}")
    private long delayMs = 120;

    public KafkaUserConsumer(UserOrchestratorService service, SqsUserProducer sqsProducer, ObjectMapper objectMapper, KafkaUserProducer kafkaProducer) {
        this.service = service;
        this.sqsProducer = sqsProducer;
        this.objectMapper = objectMapper;
        this.kafkaProducer = kafkaProducer;
    }

    @Observed(name = "messaging.consume", contextualName = "kafka-user-registration-consumer")
    @ObservationTag(key = "messaging.system", expression = "'kafka'")
    @TrackFlow("Kafka Consumer: user-registration-topic")
    @KafkaListener(topics = "user-registration-topic", groupId = "user-orchestrator-group")
    public void consume(String message) {
        log.info("Received Kafka message for registration: {}", message);
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs); // Simula processamento assíncrono para gerar curva visível de Lag no Kafka
            }
            UserRegistrationRequest request = objectMapper.readValue(message, UserRegistrationRequest.class);
            
            // Simulate provisioning steps using the Feign clients via OrchestratorService
            UserProfile profile = service.fetchAndProvisionUserProfile(request.userId());
            
            // Post audit event to SQS
            sqsProducer.publishUserAudit("USER_REGISTERED", request.userId(), "Profile status: " + profile.notificationStatus());
            kafkaProducer.publishBillingEvent(request.userId(), profile.billing().plan());
            sqsProducer.publishWelcomeEmail(request.userId());

        } catch (Exception e) {
            log.error("Failed to process user registration from Kafka", e);
            sqsProducer.publishUserAudit("REGISTRATION_FAILED", "UNKNOWN", "Error processing kafka message: " + e.getMessage());
        }
    }
}
