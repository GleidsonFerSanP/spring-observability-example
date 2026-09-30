package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.autoconfigure.binder.SqsMetricsBinder;
import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertingProperties;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@AutoConfiguration
@ConditionalOnClass(SqsTemplate.class)
@ConditionalOnProperty(prefix = "observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SqsObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(SqsAsyncClient.class)
    @ConditionalOnProperty(prefix = "observability.infrastructure.sqs-polling", name = "enabled", havingValue = "true")
    public SqsMetricsBinder sqsMetricsBinder(
            SqsAsyncClient sqsAsyncClient,
            @Autowired(required = false) AlertDispatcher alertDispatcher,
            @Autowired(required = false) AlertingProperties alertingProperties) {
        return new SqsMetricsBinder(sqsAsyncClient, alertDispatcher, alertingProperties);
    }
}
