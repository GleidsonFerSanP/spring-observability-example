package com.gleidsonfersanp.observability.observability.flow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifica um fluxo de entrada (Entrypoint) do sistema.
 * Ex: GET /api/v1/orchestrator/users/{userId} ou Kafka Consumer: user-registration-topic
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackFlow {
    String value();
}
