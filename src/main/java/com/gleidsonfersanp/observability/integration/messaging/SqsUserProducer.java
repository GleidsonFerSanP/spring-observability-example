package com.gleidsonfersanp.observability.integration.messaging;

import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import io.micrometer.observation.annotation.Observed;
import com.gleidsonfersanp.observability.observability.ObservationTag;

@Component
public class SqsUserProducer {

    private static final Logger log = LoggerFactory.getLogger(SqsUserProducer.class);
    private final SqsTemplate sqsTemplate;

    public SqsUserProducer(SqsTemplate sqsTemplate) {
        this.sqsTemplate = sqsTemplate;
    }

    @Observed(name = "messaging.produce", contextualName = "sqs-user-audit-produce")
    @ObservationTag(key = "messaging.system", expression = "'sqs'")
    @ObservationTag(key = "queue", expression = "'user-audit-queue'")
    public void publishUserAudit(String action, String userId, String details) {
        String payload = String.format("[%s] User: %s - %s", action, userId, details);
        log.info("Publishing user audit event to SQS: {}", payload);
        sqsTemplate.send(to -> to.queue("user-audit-queue").payload(payload));
    }

    @Observed(name = "messaging.produce", contextualName = "sqs-welcome-produce")
    @ObservationTag(key = "messaging.system", expression = "'sqs'")
    @ObservationTag(key = "queue", expression = "'welcome-email-queue'")
    public void publishWelcomeEmail(String userId) {
        log.info("Publishing welcome email event to SQS: {}", userId);
        sqsTemplate.send(to -> to.queue("welcome-email-queue").payload(userId));
    }
}
