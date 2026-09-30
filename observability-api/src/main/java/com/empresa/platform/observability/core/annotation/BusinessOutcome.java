package com.empresa.platform.observability.core.annotation;

public final class BusinessOutcome {
    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
    public static final String FALLBACK_APPLIED = "FALLBACK_APPLIED";

    private BusinessOutcome() {}
}
