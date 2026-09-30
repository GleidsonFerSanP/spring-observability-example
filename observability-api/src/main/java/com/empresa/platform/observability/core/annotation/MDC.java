package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.*;

/**
 * Anotação declarativa para enriquecimento automático do Mapped Diagnostic Context (MDC)
 * do SLF4J / Logback a partir de parâmetros de método ou expressões SpEL.
 *
 * <p>Esta anotação elimina 100% da necessidade de invocar {@code org.slf4j.MDC.put()} e
 * {@code org.slf4j.MDC.remove()} manualmente dentro de classes de domínio, serviços e controllers.</p>
 *
 * <h3>Principais Características:</h3>
 * <ul>
 *   <li><b>Semântica de Pilha (Stack Semantics):</b> Se uma chave já existir no MDC (ex: chamada aninhada),
 *       o valor anterior é preservado e restaurado assim que o método termina.</li>
 *   <li><b>Isolamento e Limpeza Garantida:</b> Ao término da execução (mesmo em caso de exceção),
 *       o aspecto executa a limpeza no bloco {@code finally}, prevenindo contaminação de contexto
 *       (MDC leakage) em pools de threads do Tomcat, Undertow ou {@code @Async}.</li>
 *   <li><b>Suporte a SpEL:</b> Permite navegar em grafos de objetos complexos (ex: {@code #request.userId},
 *       {@code #order.customer.id}).</li>
 *   <li><b>Injeção Direta de Parâmetro:</b> Quando anotado diretamente em um parâmetro do método,
 *       a expressão é opcional; o valor do parâmetro é convertido automaticamente para String.</li>
 * </ul>
 *
 * <h3>Exemplo de Uso no Controller (SpEL em DTO de Entrada):</h3>
 * <pre>{@code
 * @RestController
 * public class OrderController {
 *
 *     @PostMapping("/orders")
 *     @TrackFlow(name = "checkout-flow")
 *     @MDC(key = "tenant", expression = "#request.tenantId")
 *     @MDC(key = "orderId", expression = "#request.orderId")
 *     public ResponseEntity<OrderResponse> checkout(@RequestBody CheckoutRequest request) {
 *         // Todos os logs aqui e downstream conterão [tenant=..., orderId=...]
 *         log.info("Recebendo solicitação de checkout");
 *         return ResponseEntity.ok(orderService.process(request));
 *     }
 * }
 * }</pre>
 *
 * <h3>Exemplo de Uso Direto em Parâmetro de Método:</h3>
 * <pre>{@code
 * @Service
 * public class UserService {
 *
 *     @TrackStep(name = "find-user", type = ComponentType.DATABASE)
 *     public User findById(@MDC(key = "userId") String userId) {
 *         log.info("Buscando usuário no repositório"); // Log conterá [userId=123]
 *         return repository.findById(userId);
 *     }
 * }
 * }</pre>
 *
 * <h3>Exemplo de Captura de Retorno (#result):</h3>
 * <pre>{@code
 * @MDC(key = "transactionStatus", expression = "#result?.status()")
 * public TransactionResult processTransaction(TransactionRequest request) {
 *     return gateway.charge(request);
 * }
 * }</pre>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see MDCs
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(MDCs.class)
public @interface MDC {

    /**
     * Nome da chave canônica a ser inserida no MDC (ex: "userId", "orderId", "tenant").
     *
     * @return nome da chave no MDC
     */
    String key() default "";

    /**
     * Alias semântico para {@link #key()}.
     *
     * @return nome da chave no MDC
     */
    String name() default "";

    /**
     * Alias posicional para {@link #key()}, permitindo uso abreviado como {@code @MDC("userId")}.
     *
     * @return nome da chave no MDC
     */
    String value() default "";

    /**
     * Expressão Spring Expression Language (SpEL) para extrair o valor dinamicamente
     * a partir dos argumentos do método (ex: {@code "#request.userId"}), de variáveis locais
     * ou do retorno (ex: {@code "#result.status()"}).
     *
     * <p>Se o {@code @MDC} for aplicado diretamente a um parâmetro de método e esta expressão
     * for omitida, o próprio argumento será convertido para {@link String} e atribuído à chave.</p>
     *
     * @return expressão SpEL de extração
     */
    String expression() default "";
}
