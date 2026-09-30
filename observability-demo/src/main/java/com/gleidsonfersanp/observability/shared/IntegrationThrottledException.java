package com.gleidsonfersanp.observability.shared;

public class IntegrationThrottledException extends RuntimeException {
    public IntegrationThrottledException(String message) {
        super(message);
    }
}
