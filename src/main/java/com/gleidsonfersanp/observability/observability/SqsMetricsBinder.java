package com.gleidsonfersanp.observability.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SqsMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(SqsMetricsBinder.class);
    private final SqsAsyncClient sqsAsyncClient;
    private final Map<String, AtomicInteger> queueSizes = new ConcurrentHashMap<>();

    public SqsMetricsBinder(SqsAsyncClient sqsAsyncClient) {
        this.sqsAsyncClient = sqsAsyncClient;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Thread poller = new Thread(() -> {
            while (true) {
                try {
                    // Poll all queues every 10s
                    sqsAsyncClient.listQueues(ListQueuesRequest.builder().build())
                        .thenAccept(response -> {
                            for (String queueUrl : response.queueUrls()) {
                                String queueName = extractQueueName(queueUrl);
                                
                                // Register gauge dynamically if not already registered
                                AtomicInteger queueSize = queueSizes.computeIfAbsent(queueName, k -> {
                                    AtomicInteger size = new AtomicInteger(0);
                                    Gauge.builder("sqs.queue.depth", size, AtomicInteger::get)
                                            .description("Approximate number of messages visible in SQS queue")
                                            .tag("queue", queueName)
                                            .register(registry);
                                    return size;
                                });

                                // Fetch depth for this queue
                                sqsAsyncClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                                        .queueUrl(queueUrl)
                                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                                        .build())
                                .thenAccept(attr -> {
                                    int count = Integer.parseInt(attr.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
                                    queueSize.set(count);
                                }).exceptionally(ex -> {
                                    log.warn("Could not fetch attributes for {}: {}", queueName, ex.getMessage());
                                    return null;
                                });
                            }
                        }).exceptionally(ex -> {
                            log.warn("Could not list SQS queues: {}", ex.getMessage());
                            return null;
                        });

                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        poller.setDaemon(true);
        poller.start();
    }

    private String extractQueueName(String queueUrl) {
        return queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
    }
}
