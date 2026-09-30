package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Anotação para rastreamento forense de Legs (Pernas de Comunicação) com auditoria de payload e mascaramento SpEL.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LogLeg {

    /**
     * Nome do componente ou serviço alvo da perna (ex: "billing-service", "customer-service").
     */
    String target() default "";

    /**
     * Tipo da perna: INBOUND (entrada), OUTBOUND (saída externa) ou INTERNAL.
     */
    LegType type() default LegType.OUTBOUND;

    /**
     * Se verdadeiro, serializa e audita os payloads de requisição e resposta (com máscaras aplicadas).
     * O padrão corporativo é false (opt-in explícito).
     */
    boolean includePayload() default false;

    /**
     * Regras de mascaramento de dados sensíveis avaliadas dinamicamente via SpEL.
     */
    MaskField[] mask() default {};
}
