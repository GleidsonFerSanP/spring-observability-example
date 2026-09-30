package com.gleidsonfersanp.observability.integration.messaging;

import com.gleidsonfersanp.observability.domain.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaUserConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaUserConsumer.class);
    private final AuditLogRepository auditLogRepository;

    public KafkaUserConsumer(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @KafkaListener(topics = "user-events", groupId = "user-orchestrator-group")
    public void consume(String payload) {
        log.info("Received user event via Kafka: {}", payload);
        auditLogRepository.addLog("Kafka - " + payload);
    }
}
