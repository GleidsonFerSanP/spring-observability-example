package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declaração de regra de mascaramento dinâmico para campos sensíveis (LGPD / PCI-DSS)
 * em payloads auditados pela anotação {@link LogLeg @LogLeg}.
 *
 * <p>Utilizada no atributo {@link LogLeg#mask()} para identificar expressões SpEL
 * ou nomes de propriedades JSON que resolvem nós do payload que devem ser ofuscados
 * antes da emissão do log forense de auditoria.</p>
 *
 * <h3>Exemplo de Uso:</h3>
 * <pre>{@code
 * @LogLeg(
 *     target = "payment-gateway",
 *     includePayload = true,
 *     mask = {
 *         @MaskField(expression = "cardNumber", pattern = MaskPattern.CARD_PARTIAL),
 *         @MaskField(expression = "cvv", pattern = MaskPattern.PASSWORD),
 *         @MaskField(expression = "customerEmail", pattern = MaskPattern.EMAIL_PARTIAL)
 *     }
 * )
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
     * Expressão SpEL ou nome da propriedade do payload que localiza o campo sensível a ser mascarado.
     *
     * <h4>Formatos Aceitos:</h4>
     * <ul>
     *   <li><b>Nome direto da propriedade:</b> {@code "email"}, {@code "cardNumber"}, {@code "cpf"}, {@code "password"}.
     *       O motor busca a propriedade no objeto ou no nó JSON correspondente.</li>
     *   <li><b>Expressão de caminho navegável:</b> {@code "customer.personalData.cpf"}, {@code "paymentInfo.card.number"}.</li>
     *   <li><b>Expressão SpEL sobre argumentos:</b> Permite referenciar argumentos específicos do método,
     *       como {@code "#request.billingAddress.zipCode"}.</li>
     * </ul>
     *
     * @return expressão SpEL ou nome da propriedade JSON a ser mascarada
     */
    String expression();

    /**
     * Padrão semântico pré-configurado de mascaramento a ser aplicado sobre o valor do campo.
     *
     * <h4>Padrões Disponíveis:</h4>
     * <ul>
     *   <li>{@link MaskPattern#FULL_MASK}: Substituição integral do conteúdo por {@code "***REDACTED***"}. (Padrão)</li>
     *   <li>{@link MaskPattern#PASSWORD}: Ofuscação de credenciais por asteriscos {@code "********"}.</li>
     *   <li>{@link MaskPattern#CARD_PARTIAL}: Mascaramento compatível com PCI-DSS, preservando apenas os 4 últimos dígitos
     *       (ex: {@code "************1234"}).</li>
     *   <li>{@link MaskPattern#CPF_PARTIAL}: Mascaramento de CPF brasileiro preservando os dígitos iniciais e finais
     *       para auditoria (ex: {@code "123.***.***-45"}).</li>
     *   <li>{@link MaskPattern#EMAIL_PARTIAL}: Mascaramento de e-mails preservando inicial, final do usuário e domínio
     *       (ex: {@code "j***e@dominio.com"}).</li>
     * </ul>
     *
     * @return padrão semântico de mascaramento
     */
    MaskPattern pattern() default MaskPattern.FULL_MASK;

    /**
     * Máscara textual estática personalizada (opcional).
     *
     * <p>Se informada, esta string sobrepõe o algoritmo de {@link #pattern()} e substitui o valor
     * do campo diretamente pelo texto configurado (ex: {@code "[OCULTO-AUDITORIA]"}, {@code "N/A"}).</p>
     *
     * @return máscara estática customizada, ou string vazia para utilizar o {@link #pattern()}
     */
    String customMask() default "";
}
