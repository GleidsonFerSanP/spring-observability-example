package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declaração de regra de mascaramento dinâmico para campos sensíveis (LGPD / PCI-DSS)
 * em payloads auditados pela anotação {@link LogLeg @LogLeg}.
 *
 * <p>Utilizada no atributo {@link LogLeg#mask()} para identificar expressões SpEL
 * que resolvem nós ou propriedades do payload que devem ser ofuscadas antes do log.</p>
 *
 * <h3>Exemplo:</h3>
 * <pre>{@code
 * @LogLeg(target = "payment-gateway", includePayload = true,
 *         mask = {
 *             @MaskField(expression = "cardNumber", pattern = MaskPattern.CARD_PARTIAL),
 *             @MaskField(expression = "cvv", pattern = MaskPattern.PASSWORD)
 *         })
 * public PaymentResponse pay(PaymentRequest request) { ... }
 * }</pre>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see LogLeg
 * @see MaskPattern
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface MaskField {

    /**
     * Expressão SpEL ou nome da propriedade JSON que resolve o campo a ser mascarado.
     *
     * @return expressão SpEL
     */
    String expression();

    /**
     * Padrão semântico de mascaramento pré-configurado.
     * Padrão: {@link MaskPattern#FULL_MASK}.
     *
     * @return padrão de mascaramento
     */
    MaskPattern pattern() default MaskPattern.FULL_MASK;

    /**
     * Máscara textual estática personalizada (opcional, sobrepõe o {@link #pattern()} se informada).
     *
     * @return máscara estática customizada
     */
    String customMask() default "";
}
