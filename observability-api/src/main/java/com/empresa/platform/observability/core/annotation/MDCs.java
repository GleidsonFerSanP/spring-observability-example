package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

/**
 * Contêiner para permitir a repetição da anotação {@link MDC} no mesmo método, classe ou parâmetro.
 *
 * <p>Permite declarar múltiplas chaves MDC simultâneas em um único ponto de corte:</p>
 * <pre>{@code
 * @MDC(key = "tenant", expression = "#req.tenant")
 * @MDC(key = "userId", expression = "#req.userId")
 * public void handle(Request req) { ... }
 * }</pre>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see MDC
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MDCs {

    /**
     * Matriz de anotações {@link MDC} declaradas.
     *
     * @return array de anotações MDC
     */
    MDC[] value();
}
