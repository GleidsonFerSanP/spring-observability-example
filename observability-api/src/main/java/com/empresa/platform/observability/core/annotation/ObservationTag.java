package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

/**
 * Anotação declarativa para enriquecer observações do Micrometer (Tracing e Métricas)
 * em tempo de execução via expressões SpEL (Spring Expression Language).
 *
 * <p>Esta anotação tem responsabilidade exclusiva de conectar atributos dinâmicos aos Spans
 * do Tracing Distribuído (OpenTelemetry / Datadog APM) e às séries temporais de Métricas
 * (Prometheus / Micrometer Timer).</p>
 *
 * <h3>Regra de Proteção de Cardinalidade:</h3>
 * <ul>
 *   <li><b>Baixa Cardinalidade (Métricas):</b> Tags com poucos valores discretos (ex: {@code status="SUCCESS"},
 *       {@code cache="HIT"}). São anexadas diretamente às métricas de Timer/Counter do Prometheus.</li>
 *   <li><b>Alta Cardinalidade (Tracing):</b> Tags com identificadores dinâmicos (ex: {@code orderId},
 *       {@code customerId}). São roteadas exclusivamente como atributos/tags do Span do Tracing,
 *       protegendo o TSDB (Prometheus/Datadog) contra esgotamento de memória (explosão de cardinalidade).</li>
 * </ul>
 *
 * <h3>Exemplo de Uso:</h3>
 * <pre>{@code
 * @Observed(name = "payment.gateway")
 * @TrackStep(name = "charge-card", type = ComponentType.FEIGN)
 * @ObservationTag(key = "provider", expression = "#gatewayProvider", lowCardinality = true)
 * @ObservationTag(key = "retry_count", expression = "#retries", lowCardinality = true)
 * public ChargeResult execute(String gatewayProvider, int retries) {
 *     return client.call(gatewayProvider);
 * }
 * }</pre>
 *
 * <p><b>Nota sobre Logs:</b> Para enriquecer o contexto de linhas de log (SLF4J MDC),
 * utilize {@link MDC @MDC} em vez desta anotação.</p>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see ObservationTags
 * @see MDC
 * @see TrackStep
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ObservationTags.class)
public @interface ObservationTag {

    /**
     * Nome da chave da tag no contexto de observação do Micrometer.
     *
     * @return nome da tag
     */
    String key();

    /**
     * Expressão Spring Expression Language (SpEL) para avaliar o valor da tag
     * a partir dos parâmetros de entrada ou retorno do método (ex: {@code "#provider"}, {@code "#result.status()"}).
     *
     * @return expressão SpEL de extração
     */
    String expression() default "";

    /**
     * Indica se a tag é de baixa cardinalidade (adequada para séries temporais de métricas).
     * Padrão: {@code true}.
     *
     * @return se deve ser registrada como tag de métrica
     */
    boolean lowCardinality() default true;

    /**
     * Indica se a tag é de alta cardinalidade (adequada estritamente para Spans de Tracing).
     * Padrão: {@code false}.
     *
     * @return se deve ser restrita a atributos de span
     */
    boolean highCardinality() default false;
}
