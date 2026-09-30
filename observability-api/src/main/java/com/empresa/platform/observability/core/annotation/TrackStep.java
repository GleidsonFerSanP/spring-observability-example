package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

/**
 * Demarca a execução de uma <b>Etapa ou Subprocesso (Step)</b> dentro de um fluxo de negócio.
 *
 * <p>Deve ser aplicada em métodos de serviços, componentes de persistência, clientes de integração
 * ou etapas lógicas para fatiar temporalmente o tempo total da transação.</p>
 *
 * <h3>Capacidades Providas pelo Starter:</h3>
 * <ul>
 *   <li><b>Span Filho no Tracing:</b> Cria um Span filho subordinado ao {@link TrackFlow @TrackFlow} ativo.</li>
 *   <li><b>MDC Automático:</b> Injeta as chaves {@code step} e {@code step.type} no SLF4J MDC,
 *       restaurando os valores anteriores no bloco {@code finally} (permitindo steps aninhados).</li>
 *   <li><b>Fatiamento Temporal (Latency Attribution):</b> Permite ao motor {@code LatencyAttributionEngine}
 *       calcular a porcentagem exata de tempo consumido por tipo de tecnologia (ex: Banco de Dados vs
 *       Integrações Externas vs Processamento Interno).</li>
 *   <li><b>Vigilância de SLA do Subprocesso:</b> Se a duração do step exceder o limiar configurado
 *       em {@code observability.alerting.step-sla-ms}, um alarme {@code INTEGRATION_LATENCY_SLA_BREACH}
 *       é despachado via {@code AlertDispatcher}.</li>
 * </ul>
 *
 * <h3>Exemplo de Uso:</h3>
 * <pre>{@code
 * @Service
 * public class PaymentService {
 *
 *     @TrackStep(name = "charge-credit-card", type = ComponentType.FEIGN)
 *     public ChargeResult executePayment(PaymentRequest request) {
 *         // Logs aqui conterão [flow=..., step=charge-credit-card, step.type=FEIGN]
 *         log.info("Invocando gateway externo de pagamento");
 *         return feignClient.charge(request);
 *     }
 * }
 * }</pre>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see TrackFlow
 * @see ComponentType
 * @see MDC
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TrackStep {

    /**
     * Alias para {@link #name()}.
     *
     * @return identificador do subprocesso
     */
    String value() default "";

    /**
     * Identificador canônico do step (ex: "validate-cart", "reserve-inventory", "charge-card").
     *
     * @return identificador do subprocesso
     */
    String name() default "";

    /**
     * Categoria canônica do componente de software executado nesta etapa,
     * baseada no enum {@link ComponentType}.
     * Padrão: {@link ComponentType#BUSINESS}.
     *
     * @return categoria do componente
     */
    ComponentType type() default ComponentType.BUSINESS;
}
