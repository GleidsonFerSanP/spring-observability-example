package com.gleidsonfersanp.observability.integration;

import com.empresa.platform.observability.core.annotation.LegType;
import com.empresa.platform.observability.core.annotation.LogLeg;
import com.empresa.platform.observability.core.annotation.MaskField;
import com.empresa.platform.observability.core.annotation.MaskPattern;
import com.empresa.platform.observability.core.annotation.ObservationTag;
import com.empresa.platform.observability.core.annotation.TrackStep;
import com.gleidsonfersanp.observability.domain.BillingDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "billing-service", url = "${app.integrations.billing.url}")
public interface BillingClient {

    @ObservationTag(key = "client", value = "billing")
    @ObservationTag(key = "billing_type", expression = "#result?.billingType()?.name()")
    @TrackStep("API Billing (GET /billing/accounts/{userId})")
    @LogLeg(
        target = "billing-service",
        type = LegType.OUTBOUND,
        mask = {
            @MaskField(
                expression = "#result?.plan()",
                pattern = MaskPattern.FULL_MASK
            )
        }
    )
    @CircuitBreaker(name = "billing-service")
    @GetMapping("/billing/accounts/{userId}")
    BillingDto getBillingInfo(@PathVariable("userId") String userId);
}
