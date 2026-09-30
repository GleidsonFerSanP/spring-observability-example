package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ObservationTags.class)
public @interface ObservationTag {
    String key();
    String expression();
    boolean lowCardinality() default true;
    boolean highCardinality() default false;
}
