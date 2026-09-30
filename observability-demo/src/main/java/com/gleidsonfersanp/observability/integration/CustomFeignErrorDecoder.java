package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.shared.IntegrationBadRequestException;
import com.gleidsonfersanp.observability.shared.IntegrationServerException;
import com.gleidsonfersanp.observability.shared.IntegrationThrottledException;
import com.gleidsonfersanp.observability.shared.ResourceNotFoundException;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CustomFeignErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(CustomFeignErrorDecoder.class);
    private final ErrorDecoder defaultErrorDecoder = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        log.error("Feign Error [{}]: Status {} for url: {}", methodKey, response.status(), response.request().url());

        return switch (response.status()) {
            case 400 -> new IntegrationBadRequestException("Bad request to external service: " + methodKey);
            case 401, 403 -> new IntegrationServerException("Authentication/Authorization failed calling external service: " + methodKey);
            case 404 -> new ResourceNotFoundException("Resource not found calling external service: " + methodKey);
            case 429 -> new IntegrationThrottledException("Too many requests to external service: " + methodKey);
            case 500, 502, 503, 504 -> new IntegrationServerException("Server error from external service: " + methodKey);
            default -> defaultErrorDecoder.decode(methodKey, response);
        };
    }
}
