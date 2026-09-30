package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

/**
 * Anotação declarativa para demarcar dimensões canônicas de fluxo de negócio (ex: {@code tenant},
 * {@code region}, {@code channel}, {@code partner}) associadas à execução de um {@link TrackFlow @TrackFlow}.
 *
 * <p>As dimensões declaradas são incorporadas ao {@code FlowContext} e ao modelo temporal
 * {@code LatencyAttributionEngine}, permitindo que métricas de latência e alarmística de SLAs
 * sejam agregadas, fatiadas e filtradas por dimensão de negócio em dashboards do Grafana e Datadog.</p>
 *
 * <h3>Exemplo de Uso em Parâmetro de Controller:</h3>
 * <pre>{@code
 * @PostMapping("/checkout")
 * @TrackFlow(name = "checkout-flow")
 * public ResponseEntity<OrderResponse> checkout(@FlowDimension(key = "tenant") String tenant,
 *                                               @RequestBody OrderRequest request) {
 *     return ResponseEntity.ok(orderService.process(request));
 * }
 * }</pre>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see TrackFlow
 * @see FlowDimensionsTag
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(FlowDimensionsTag.class)
public @interface FlowDimension {

    /**
     * Nome da chave da dimensão (ex: "tenant", "channel", "region").
     *
     * @return chave da dimensão
     */
    String key() default "";

    /**
     * Alias para {@link #key()}.
     *
     * @return nome da dimensão
     */
    String name() default "";

    /**
     * Valor fixo da dimensão quando utilizada no nível de método ou classe.
     *
     * @return valor fixo
     */
    String value() default "";

    /**
     * Expressão SpEL opcional para extrair o valor da dimensão a partir de argumentos.
     *
     * @return expressão SpEL
     */
    String expression() default "";

    /**
     * Se verdadeiro, propaga esta dimensão de fluxo também para o SLF4J MDC durante a execução.
     * Padrão: {@code true}.
     *
     * @return se deve propagar para o MDC
     */
    boolean mdc() default true;
}
