package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.domain.BillingDto;
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

@FeignClient(name = "billing-service", url = "${app.integrations.billing.url}")
public interface BillingClient {

    @ObservationTag(key = "client", expression = "'billing'")
    @ObservationTag(key = "billing_type", expression = "#result?.billingType()?.name()")
    @TrackStep("API Billing (GET /billing/accounts/{userId})")
    @LogLeg(
        target = "billing-service",
        type = com.gleidsonfersanp.observability.observability.leg.LegType.OUTBOUND,
        mask = {
            @com.gleidsonfersanp.observability.observability.leg.MaskField(
                expression = "#result?.plan()",
                pattern = com.gleidsonfersanp.observability.observability.leg.MaskPattern.FULL_MASK
            )
        }
    )
    @CircuitBreaker(name = "billing-service")
    @GetMapping("/billing/accounts/{userId}")
    BillingDto getBillingInfo(@PathVariable("userId") String userId);
}
