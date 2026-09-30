package com.empresa.platform.observability.core.cardinality;

import java.util.Set;

/**
 * Denylist and cardinality guard to prevent high-cardinality keys from exploding metrics.
 */
public final class CardinalityPolicy {

    private static final Set<String> HIGH_CARDINALITY_DENYLIST = Set.of(
            "userid",
            "customerid",
            "accountid",
            "orderid",
            "requestid",
            "messageid",
            "traceid",
            "correlationid",
            "cpf",
            "email",
            "token",
            "password",
            "authorization",
            "payload"
    );

    private CardinalityPolicy() {}

    /**
     * Checks if a tag key is permitted for low-cardinality metric registries.
     */
    public static boolean isPermittedMetricTag(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
        return !HIGH_CARDINALITY_DENYLIST.contains(normalized);
    }

    public static boolean isAllowed(String key) {
        return isPermittedMetricTag(key);
    }
}
