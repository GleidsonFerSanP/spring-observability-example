package com.gleidsonfersanp.observability.integration.messaging;

import com.empresa.platform.observability.core.annotation.ObservationTag;
import com.empresa.platform.observability.core.annotation.TrackStep;
import com.empresa.platform.observability.core.correlation.CorrelationContext;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SqsUserProducer {

    private static final Logger log = LoggerFactory.getLogger(SqsUserProducer.class);
    private final SqsTemplate sqsTemplate;

    public SqsUserProducer(SqsTemplate sqsTemplate) {
        this.sqsTemplate = sqsTemplate;
    }

    @Observed(name = "messaging.produce", contextualName = "sqs-user-audit-produce")
    @ObservationTag(key = "messaging.system", value = "sqs")
    @ObservationTag(key = "queue", value = "user-audit-queue")
    @TrackStep("Publicação SQS (user-audit-queue)")
    public void publishUserAudit(String action, String userId, String details) {
        String payload = String.format("[%s] User: %s - %s", action, userId, details);
        log.info("Publishing user audit event to SQS: {}", payload);
        String cid = CorrelationContext.generateOrGet();
        sqsTemplate.send(to -> to.queue("user-audit-queue")
                .payload(payload)
                .header(CorrelationContext.CORRELATION_ID_HEADER, cid));
    }

    @Observed(name = "messaging.produce", contextualName = "sqs-welcome-produce")
    @ObservationTag(key = "messaging.system", value = "sqs")
    @ObservationTag(key = "queue", value = "welcome-email-queue")
    @TrackStep("Publicação SQS (welcome-email-queue)")
    public void publishWelcomeEmail(String userId) {
        log.info("Publishing welcome email event to SQS: {}", userId);
        String cid = CorrelationContext.generateOrGet();
        sqsTemplate.send(to -> to.queue("welcome-email-queue")
                .payload(userId)
                .header(CorrelationContext.CORRELATION_ID_HEADER, cid));
    }
}
