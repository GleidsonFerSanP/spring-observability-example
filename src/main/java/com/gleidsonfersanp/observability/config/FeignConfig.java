package com.gleidsonfersanp.observability.config;

import com.gleidsonfersanp.observability.integration.CustomFeignErrorDecoder;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {

    @Bean
    public ErrorDecoder errorDecoder() {
        return new CustomFeignErrorDecoder();
    }

    @Bean
    public RequestInterceptor correlationIdRequestInterceptor() {
        return requestTemplate -> {};
    }
}

