package com.gleidsonfersanp.observability.integration.messaging;

import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SqsWelcomeConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsWelcomeConsumer.class);

    @SqsListener("welcome-email-queue")
    public void consume(String payload) {
        log.info("Simulating welcome email send via SQS for user: {}", payload);
    }
}
