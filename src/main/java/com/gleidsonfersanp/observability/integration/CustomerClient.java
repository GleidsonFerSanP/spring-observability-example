package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.domain.CustomerDto;
import com.gleidsonfersanp.observability.observability.ObservationTag;
import com.gleidsonfersanp.observability.observability.flow.TrackStep;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "customer-service", url = "${app.integrations.customer.url}")
public interface CustomerClient {

    @ObservationTag(key = "client", expression = "'customer'")
    @TrackStep("API Customer (GET /customers/{userId})")
    @CircuitBreaker(name = "customer-service")
    @GetMapping("/customers/{userId}")
    CustomerDto getCustomerInfo(@PathVariable("userId") String userId);
}
