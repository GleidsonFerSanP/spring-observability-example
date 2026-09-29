package com.gleidsonfersanp.observability.integration;

import com.empresa.platform.observability.core.annotation.LegType;
import com.empresa.platform.observability.core.annotation.LogLeg;
import com.empresa.platform.observability.core.annotation.MaskField;
import com.empresa.platform.observability.core.annotation.MaskPattern;
import com.empresa.platform.observability.core.annotation.ObservationTag;
import com.empresa.platform.observability.core.annotation.TrackStep;
import com.gleidsonfersanp.observability.domain.CustomerDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "customer-service", url = "${app.integrations.customer.url}")
public interface CustomerClient {

    @ObservationTag(key = "client", expression = "'customer'")
    @TrackStep("API Customer (GET /customers/{userId})")
    @LogLeg(
        target = "customer-service",
        type = LegType.OUTBOUND,
        mask = {
            @MaskField(
                expression = "#result?.email()",
                pattern = MaskPattern.EMAIL_PARTIAL
            )
        }
    )
    @CircuitBreaker(name = "customer-service")
    @GetMapping("/customers/{userId}")
    CustomerDto getCustomerInfo(@PathVariable("userId") String userId);
}
