package com.gleidsonfersanp.observability.integration.messaging;

import com.gleidsonfersanp.observability.domain.AuditLogRepository;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SqsUserConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsUserConsumer.class);
    private final AuditLogRepository auditLogRepository;

    public SqsUserConsumer(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @SqsListener("user-audit-queue")
    public void consume(String payload) {
        log.info("Received user audit event via SQS: {}", payload);
        auditLogRepository.addLog("SQS - " + payload);
    }
}
