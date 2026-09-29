package com.gleidsonfersanp.observability.observability.flow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifica uma fatia/subprocesso dentro de um fluxo ativo.
 * Ex: API Customer, API Billing, Publicação Kafka, etc.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackStep {
    String value();
}
