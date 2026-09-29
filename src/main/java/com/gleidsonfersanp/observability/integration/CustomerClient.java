package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.domain.CustomerDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.gleidsonfersanp.observability.observability.ObservationTag;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@FeignClient(name = "customer-service", url = "${app.integrations.customer.url}")
public interface CustomerClient {

    @ObservationTag(key = "client", expression = "'customer'")
    @com.gleidsonfersanp.observability.observability.flow.TrackStep("API Customer (GET /customers/{userId})")
    @CircuitBreaker(name = "customer-service")
    @GetMapping("/customers/{userId}")
    CustomerDto getCustomerInfo(@PathVariable("userId") String userId);
}
