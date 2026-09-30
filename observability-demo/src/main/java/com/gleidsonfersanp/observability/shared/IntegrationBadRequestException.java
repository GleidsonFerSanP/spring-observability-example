package com.gleidsonfersanp.observability.shared;

public class IntegrationBadRequestException extends RuntimeException {
    public IntegrationBadRequestException(String message) {
        super(message);
    }
}
