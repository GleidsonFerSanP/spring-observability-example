package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Anotação para auditoria e rastreamento forense de <b>Pernas de Comunicação (Legs)</b>
 * com suporte a numeração sequencial de saltos, auditoria de latência, status e mascaramento
 * dinâmico de dados sensíveis (LGPD/PCI-DSS) via SpEL.
 *
 * <h3>Capacidades:</h3>
 * <ul>
 *   <li><b>Numeração Sequencial de Pernas:</b> Gera {@code leg_number} e preserva {@code leg_parent}
 *       para mapear a ordem cronológica de integrações síncronas/assíncronas na thread.</li>
 *   <li><b>MDC Transitório de Integração:</b> Durante a execução da perna, injeta no MDC
 *       {@code leg_number}, {@code leg_type}, {@code leg_target}, {@code leg_phase},
 *       {@code leg_duration_ms} e {@code leg_status}, limpando no {@code finally}.</li>
 *   <li><b>Auditoria Estruturada no Logger AUDIT_LEG_LOGGER:</b> Emite eventos JSON
 *       estruturados de {@code LEG_REQUEST}, {@code LEG_RESPONSE} e {@code LEG_ERROR}.</li>
 *   <li><b>Mascaramento SpEL:</b> Permite mascarar campos sensíveis nos payloads com regras
 *       declarativas (ex: {@link MaskPattern#EMAIL}, {@link MaskPattern#CPF}).</li>
 * </ul>
 *
 * <h3>Exemplo de Uso em Cliente Feign:</h3>
 * <pre>{@code
 * @FeignClient(name = "customer-service")
 * public interface CustomerClient {
 *
 *     @GetMapping("/customers/{id}")
 *     @LogLeg(target = "customer-service", type = LegType.OUTBOUND, includePayload = true,
 *             mask = { @MaskField(field = "email", pattern = MaskPattern.EMAIL) })
 *     CustomerDto getCustomer(@PathVariable("id") String id);
 * }
 * }</pre>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see LegType
 * @see LegPhase
 * @see MaskField
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LogLeg {

    /**
     * Nome do componente ou serviço alvo da perna (ex: "billing-service", "customer-service", "cielo-api").
     *
     * @return nome do serviço de destino
     */
    String target() default "";

    /**
     * Direção arquitetural da integração: {@link LegType#INBOUND} ou {@link LegType#OUTBOUND}.
     * Padrão: {@link LegType#OUTBOUND}.
     *
     * @return tipo da perna
     */
    LegType type() default LegType.OUTBOUND;

    /**
     * Se verdadeiro, serializa e audita os payloads de requisição e resposta (com máscaras aplicadas).
     * O padrão corporativo é {@code false} (opt-in explícito por razões de performance e privacidade).
     *
     * @return se deve auditar o payload
     */
    boolean includePayload() default false;

    /**
     * Regras de mascaramento de dados sensíveis avaliadas dinamicamente via SpEL sobre os payloads.
     *
     * @return array de regras de mascaramento
     */
    MaskField[] mask() default {};
}
