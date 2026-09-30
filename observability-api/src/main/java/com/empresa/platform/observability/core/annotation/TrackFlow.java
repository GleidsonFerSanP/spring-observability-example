package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

/**
 * Demarca o ponto de entrada canônico de um <b>Fluxo de Negócio Fim a Fim</b> (Entrypoint).
 *
 * <p>Esta anotação deve ser aplicada prioritariamente em métodos de Controllers REST,
 * Listeners de Mensageria (Kafka/SQS) ou tarefas agendadas (Schedulers) que iniciam
 * uma transação de negócio.</p>
 *
 * <h3>Capacidades Providas pelo Starter:</h3>
 * <ul>
 *   <li><b>Span Raiz no Tracing:</b> Inicializa o Span pai da transação no Datadog APM / OpenTelemetry.</li>
 *   <li><b>MDC Automático:</b> Injeta a chave canônica {@code flow} no SLF4J MDC durante toda a execução,
 *       removendo-a com segurança no bloco {@code finally}.</li>
 *   <li><b>Métricas de Latência do Fluxo:</b> Registra o Timer {@code observability.flow.duration}
 *       com as dimensões declaradas.</li>
 *   <li><b>Vigilância de SLA Fim a Fim:</b> Se a duração total do fluxo exceder o limiar configurado
 *       em {@code observability.alerting.flow-sla-ms}, um alarme {@code FLOW_LATENCY_SLA_BREACH}
 *       é despachado automaticamente via {@code AlertDispatcher}.</li>
 *   <li><b>Detecção de Interrupções:</b> Em caso de falha não tratada, incrementa o contador
 *       {@code observability.flow.interruption} etiquetado com o step causador e o tipo da exceção.</li>
 * </ul>
 *
 * <h3>Exemplo de Uso:</h3>
 * <pre>{@code
 * @RestController
 * @RequestMapping("/orders")
 * public class OrderController {
 *
 *     @PostMapping
 *     @TrackFlow(name = "order-checkout-flow", type = "HTTP")
 *     @MDC(key = "tenant", expression = "#request.tenantId")
 *     public ResponseEntity<OrderResponse> checkout(@RequestBody OrderRequest request) {
 *         return ResponseEntity.ok(orderService.process(request));
 *     }
 * }
 * }</pre>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see TrackStep
 * @see FlowDimension
 * @see MDC
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface TrackFlow {

    /**
     * Alias para {@link #name()}.
     *
     * @return identificador do fluxo
     */
    String value() default "";

    /**
     * Identificador canônico do fluxo de negócio (ex: "user-onboarding-flow", "order-checkout-flow").
     *
     * @return identificador do fluxo
     */
    String name() default "";

    /**
     * Tipo do protocolo ou gatilho de entrada (ex: "HTTP", "KAFKA", "SQS", "SCHEDULER").
     * Padrão: {@code "HTTP"}.
     *
     * @return tipo de entrada
     */
    String type() default "HTTP";

    /**
     * Variante estática da arquitetura ou rota (ex: "legacy", "new").
     * Se omitido, pode ser resolvido dinamicamente via {@code FlowVariantProvider} ou Feature Flags.
     *
     * @return variante do fluxo
     */
    String variant() default "";
}
