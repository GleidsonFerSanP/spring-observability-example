package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.core.correlation.CorrelationContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
@ConditionalOnProperty(prefix = "observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeignObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "observabilityFeignRequestInterceptor")
    @ConditionalOnProperty(prefix = "observability.feign", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RequestInterceptor observabilityFeignRequestInterceptor() {
        return (RequestTemplate requestTemplate) -> {
            String correlationId = CorrelationContext.generateOrGet();
            if (correlationId != null && !correlationId.isBlank()) {
                requestTemplate.header(CorrelationContext.CORRELATION_ID_HEADER, correlationId);
                requestTemplate.header("x-correlation-id", correlationId);
            }

            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                requestTemplate.header("x-trace-id", traceId);
            }
        };
    }
}
