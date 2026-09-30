package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declaração de mascaramento de campo sensível avaliado via SpEL.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface MaskField {

    /**
     * Expressão SpEL que resolve o campo a ser mascarado.
     */
    String expression();

    /**
     * Padrão semântico de mascaramento.
     */
    MaskPattern pattern() default MaskPattern.FULL_MASK;

    /**
     * Máscara textual estática personalizada (opcional, sobrepõe o pattern se informada).
     */
    String customMask() default "";
}
