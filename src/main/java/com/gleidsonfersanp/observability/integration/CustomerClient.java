package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.domain.CustomerDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "customer-service", url = "${app.integrations.customer.url}")
public interface CustomerClient {

    @CircuitBreaker(name = "customer-service")
    @GetMapping("/customers/{userId}")
    CustomerDto getCustomerInfo(@PathVariable("userId") String userId);
}
