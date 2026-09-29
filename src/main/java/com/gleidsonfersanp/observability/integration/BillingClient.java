package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.domain.BillingDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "billing-service", url = "${app.integrations.billing.url}")
public interface BillingClient {

    @CircuitBreaker(name = "billing-service")
    @GetMapping("/billing/accounts/{userId}")
    BillingDto getBillingInfo(@PathVariable("userId") String userId);
}
