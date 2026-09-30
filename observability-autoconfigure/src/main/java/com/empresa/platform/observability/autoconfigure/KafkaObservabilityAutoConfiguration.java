package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.autoconfigure.binder.KafkaLagMetricsBinder;
import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertingProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaObservabilityAutoConfiguration {

    @Bean
    public BeanPostProcessor kafkaObservabilityBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof KafkaTemplate<?, ?> template) {
                    template.setObservationEnabled(true);
                } else if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                    factory.getContainerProperties().setObservationEnabled(true);
                }
                return bean;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.infrastructure.kafka-lag", name = "enabled", havingValue = "true")
    public KafkaLagMetricsBinder kafkaLagMetricsBinder(
            KafkaAdmin kafkaAdmin,
            @Autowired(required = false) AlertDispatcher alertDispatcher,
            @Autowired(required = false) AlertingProperties alertingProperties) {
        return new KafkaLagMetricsBinder(kafkaAdmin, alertDispatcher, alertingProperties);
    }
}
