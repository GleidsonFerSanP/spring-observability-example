package com.gleidsonfersanp.observability.domain;

public record BillingDto(
        String plan,
        String status,
        BillingType billingType
) {}
