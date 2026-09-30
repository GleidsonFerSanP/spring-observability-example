package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

/**
 * Contêiner para permitir a repetição da anotação {@link FlowDimension} no mesmo método, classe ou parâmetro.
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see FlowDimension
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface FlowDimensionsTag {

    /**
     * Matriz de anotações {@link FlowDimension} declaradas.
     *
     * @return array de dimensões
     */
    FlowDimension[] value();
}
