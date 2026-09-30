package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(FlowDimensionsTag.class)
public @interface FlowDimension {
    String key() default "";
    String name() default "";
    String value() default "";
    String expression() default "";
    boolean mdc() default true;
}
