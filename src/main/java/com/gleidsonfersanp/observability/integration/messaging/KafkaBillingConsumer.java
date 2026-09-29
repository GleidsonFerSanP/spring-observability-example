package com.gleidsonfersanp.observability.integration.messaging;

import com.gleidsonfersanp.observability.observability.ObservationTag;
import com.gleidsonfersanp.observability.observability.correlation.CorrelationContext;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class KafkaBillingConsumer {
    private static final Logger log = LoggerFactory.getLogger(KafkaBillingConsumer.class);

    @Observed(name = "messaging.consume", contextualName = "kafka-billing-consumer")
    @ObservationTag(key = "messaging.system", expression = "'kafka'")
    @KafkaListener(topics = "billing-events-topic", groupId = "billing-group")
    public void consume(String message,
                        @Header(name = CorrelationContext.CORRELATION_ID_HEADER, required = false) byte[] correlationIdBytes) {
        String cid = (correlationIdBytes != null)
                ? new String(correlationIdBytes, StandardCharsets.UTF_8)
                : null;
        CorrelationContext.runWithCorrelationId(cid, () -> {
            log.info("Received Kafka billing event: {}", message);
            try { Thread.sleep(80); } catch (InterruptedException e) {}
        });
    }
}
