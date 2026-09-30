package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ObservationTags.class)
public @interface ObservationTag {
    String key();
    String expression() default "";
    boolean lowCardinality() default true;
    boolean highCardinality() default false;
    boolean mdc() default true;
}
