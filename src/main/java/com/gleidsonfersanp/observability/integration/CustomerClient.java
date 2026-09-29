package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.domain.CustomerDto;
import com.gleidsonfersanp.observability.observability.ObservationTag;
import com.gleidsonfersanp.observability.observability.flow.TrackStep;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import com.gleidsonfersanp.observability.observability.leg.LegType;
import com.gleidsonfersanp.observability.observability.leg.LogLeg;
import com.gleidsonfersanp.observability.observability.leg.MaskField;
import com.gleidsonfersanp.observability.observability.leg.MaskPattern;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "customer-service", url = "${app.integrations.customer.url}")
public interface CustomerClient {

    @ObservationTag(key = "client", expression = "'customer'")
    @TrackStep("API Customer (GET /customers/{userId})")
    @LogLeg(
        target = "customer-service",
        type = com.gleidsonfersanp.observability.observability.leg.LegType.OUTBOUND,
        mask = {
            @com.gleidsonfersanp.observability.observability.leg.MaskField(
                expression = "#result?.email()",
                pattern = com.gleidsonfersanp.observability.observability.leg.MaskPattern.EMAIL_PARTIAL
            )
        }
    )
    @CircuitBreaker(name = "customer-service")
    @GetMapping("/customers/{userId}")
    CustomerDto getCustomerInfo(@PathVariable("userId") String userId);
}
