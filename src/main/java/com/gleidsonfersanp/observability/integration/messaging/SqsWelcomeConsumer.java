package com.gleidsonfersanp.observability.integration.messaging;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import io.micrometer.observation.annotation.Observed;
import com.gleidsonfersanp.observability.observability.ObservationTag;

@Component
public class SqsWelcomeConsumer {
    private static final Logger log = LoggerFactory.getLogger(SqsWelcomeConsumer.class);

    @Observed(name = "messaging.consume", contextualName = "sqs-welcome-consumer")
    @ObservationTag(key = "messaging.system", expression = "'sqs'")
    @SqsListener("welcome-email-queue")
    public void consume(String message) {
        log.info("Received SQS welcome email event: {}", message);
        try { Thread.sleep(200); } catch (InterruptedException e) {}
    }
}
