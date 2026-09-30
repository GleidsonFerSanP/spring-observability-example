package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.autoconfigure.resilience.CircuitBreakerAlertListener;
import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(CircuitBreaker.class)
@ConditionalOnProperty(prefix = "observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResilienceObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.resilience", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CircuitBreakerAlertListener circuitBreakerAlertListener(@Autowired(required = false) AlertDispatcher alertDispatcher) {
        return new CircuitBreakerAlertListener(alertDispatcher);
    }
}
