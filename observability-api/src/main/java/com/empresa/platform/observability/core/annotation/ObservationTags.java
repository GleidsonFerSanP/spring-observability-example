package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ObservationTags {
    ObservationTag[] value();
}
