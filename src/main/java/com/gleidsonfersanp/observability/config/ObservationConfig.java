package com.gleidsonfersanp.observability.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Configuration
public class ObservationConfig {

    @Aspect
    @Order(10)
    public static class OrderedObservedAspect extends ObservedAspect implements Ordered {
        private final int order;

        public OrderedObservedAspect(ObservationRegistry observationRegistry, int order) {
            super(observationRegistry);
            this.order = order;
        }

        @Override
        public int getOrder() {
            return this.order;
        }
    }

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new OrderedObservedAspect(observationRegistry, 10);
    }
}
