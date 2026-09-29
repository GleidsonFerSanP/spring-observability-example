package com.gleidsonfersanp.observability.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SqsMetricsBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(SqsMetricsBinder.class);
    private final SqsAsyncClient sqsAsyncClient;
    private final Map<String, AtomicInteger> queueSizes = new ConcurrentHashMap<>();
    private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();
    private MeterRegistry registry;

    @Value("${sqs.metrics.queues:}")
    private List<String> configuredQueues;

    public SqsMetricsBinder(SqsAsyncClient sqsAsyncClient) {
        this.sqsAsyncClient = sqsAsyncClient;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${sqs.metrics.poll-delay:10000}")
    public void pollQueueMetrics() {
        if (this.registry == null || configuredQueues == null || configuredQueues.isEmpty()) {
            return;
        }

        for (String queueName : configuredQueues) {
            String cachedUrl = queueUrlCache.get(queueName);
            if (cachedUrl != null) {
                fetchQueueAttributes(queueName, cachedUrl);
            } else {
                sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build())
                        .thenAccept(response -> {
                            queueUrlCache.put(queueName, response.queueUrl());
                            fetchQueueAttributes(queueName, response.queueUrl());
                        }).exceptionally(ex -> {
                            log.warn("Could not find Queue URL for {}: {}", queueName, ex.getMessage());
                            return null;
                        });
            }
        }
    }

    private void fetchQueueAttributes(String queueName, String queueUrl) {
        AtomicInteger queueSize = queueSizes.computeIfAbsent(queueName, k -> {
            AtomicInteger size = new AtomicInteger(0);
            Gauge.builder("sqs.queue.depth", size, AtomicInteger::get)
                    .description("Approximate number of messages visible in SQS queue")
                    .tag("queue", queueName)
                    .register(this.registry);
            return size;
        });

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
}
