package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

/**
 * Contêiner para permitir repetição da anotação {@link ObservationTag} no mesmo método, classe ou parâmetro.
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see ObservationTag
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ObservationTags {

    /**
     * Matriz de anotações {@link ObservationTag} declaradas.
     *
     * @return array de anotações
     */
    ObservationTag[] value();
}
