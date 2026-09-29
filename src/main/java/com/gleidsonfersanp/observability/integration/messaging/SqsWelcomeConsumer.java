package com.gleidsonfersanp.observability.integration.messaging;

import com.gleidsonfersanp.observability.observability.ObservationTag;
import com.gleidsonfersanp.observability.observability.correlation.CorrelationContext;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class SqsWelcomeConsumer {
    private static final Logger log = LoggerFactory.getLogger(SqsWelcomeConsumer.class);

    @Observed(name = "messaging.consume", contextualName = "sqs-welcome-consumer")
    @ObservationTag(key = "messaging.system", expression = "'sqs'")
    @SqsListener("welcome-email-queue")
    public void consume(String message, 
                        @Header(name = CorrelationContext.CORRELATION_ID_HEADER, required = false) String correlationId) {
        CorrelationContext.runWithCorrelationId(correlationId, () -> {
            log.info("Received SQS welcome email event: {}", message);
            try { Thread.sleep(200); } catch (InterruptedException e) {}
        });
    }
}
