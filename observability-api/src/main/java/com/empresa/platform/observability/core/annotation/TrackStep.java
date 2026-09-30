package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackStep {
    String value() default "";
    String name() default "";
    ComponentType type() default ComponentType.BUSINESS;
}
