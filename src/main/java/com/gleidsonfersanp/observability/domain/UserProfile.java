package com.gleidsonfersanp.observability.domain;

public record UserProfile(
        String userId,
        CustomerDto customer,
        BillingDto billing,
        String notificationStatus
) {}
