package com.gleidsonfersanp.observability.integration.messaging;

import com.gleidsonfersanp.observability.domain.AuditLogRepository;
import com.gleidsonfersanp.observability.observability.ObservationTag;
import com.gleidsonfersanp.observability.observability.correlation.CorrelationContext;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class SqsUserConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsUserConsumer.class);
    private final AuditLogRepository auditLogRepository;

    @org.springframework.beans.factory.annotation.Value("${app.sqs.audit-consumer.delay-ms:5000}")
    private long delayMs = 5000;

    public SqsUserConsumer(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Observed(name = "messaging.consume", contextualName = "sqs-user-audit-consumer")
    @ObservationTag(key = "messaging.system", expression = "'sqs'")
    @SqsListener("user-audit-queue")
    public void consume(String message, 
                        @Header(name = CorrelationContext.CORRELATION_ID_HEADER, required = false) String correlationId) {
        CorrelationContext.runWithCorrelationId(correlationId, () -> {
            log.info("Received SQS message, saving to audit log: {}", message);
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) {}
            }
            auditLogRepository.addLog(message);
        });
    }
}
