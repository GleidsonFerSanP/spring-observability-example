package com.empresa.platform.observability.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Anotação para auditoria e rastreamento forense de <b>Pernas de Comunicação (Communication Legs)</b>
 * com suporte a numeração sequencial de saltos, auditoria de latência, status e mascaramento
 * dinâmico de dados sensíveis (LGPD/PCI-DSS) via SpEL.
 *
 * <p>Uma <b>Perna (Leg)</b> representa um salto de rede discreto de integração com componentes externos,
 * sejam eles chamadas síncronas de saída (HTTP via Feign / WebClient / RestTemplate, gRPC), operações
 * de mensageria (Kafka Producer/Consumer, SQS, RabbitMQ), acessos a bancos de dados ou chamadas de entrada.</p>
 *
 * <h3>Capacidades Principais:</h3>
 * <ul>
 *   <li><b>Numeração Sequencial de Pernas (Leg Numbering):</b> Cada chamada anotada com {@code @LogLeg}
 *       recebe um número sequencial único na thread ({@code leg_number} = 1, 2, 3...) e preserva
 *       o identificador da perna pai ({@code leg_parent}) permitindo rastrear árvores e cadeias de chamadas.</li>
 *   <li><b>Injeção Transitória no MDC:</b> Durante a execução da perna, o interceptor injeta no SLF4J MDC
 *       as chaves estruturadas:
 *       <ul>
 *         <li>{@code leg_number}: Número sequencial do salto na thread corrente.</li>
 *         <li>{@code leg_parent}: Número da perna pai em caso de pernas aninhadas (se aplicável).</li>
 *         <li>{@code leg_type}: Direção arquitetural ({@code OUTBOUND} ou {@code INBOUND}).</li>
 *         <li>{@code leg_target}: Identificador semântico do serviço ou recurso de destino.</li>
 *         <li>{@code leg_phase}: Fase atual do ciclo de vida da perna ({@code REQUEST} ou {@code RESPONSE}).</li>
 *         <li>{@code leg_duration_ms}: Duração total do salto em milissegundos (injetado na resposta/falha).</li>
 *         <li>{@code leg_status}: Status final da perna ({@code SUCCESS} ou {@code FAILED}).</li>
 *       </ul>
 *       Todas as chaves do MDC são limpas com garantia no bloco {@code finally} ao término do método.</li>
 *   <li><b>Auditoria Forense Estruturada no Logger {@code AUDIT_LEG_LOGGER}:</b> Emite eventos JSON
 *       estruturados com o esquema canônico contendo {@code event}, {@code legNumber}, {@code type},
 *       {@code target}, {@code phase}, {@code status}, {@code durationMs} e opcionalmente payloads mascarados.</li>
 *   <li><b>Mascaramento Dinâmico de Dados Sensíveis (SpEL Masking):</b> Integração com regras declarativas
 *       {@link MaskField} para ofuscar campos confidenciais (cartão de crédito, CPF, e-mail, senhas)
 *       garantindo conformidade com LGPD e PCI-DSS antes de emitir os logs.</li>
 * </ul>
 *
 * <h3>Exemplo de Uso em Cliente Feign:</h3>
 * <pre>{@code
 * @FeignClient(name = "customer-service")
 * public interface CustomerClient {
 *
 *     @GetMapping("/customers/{id}")
 *     @LogLeg(
 *         target = "customer-service",
 *         type = LegType.OUTBOUND,
 *         includePayload = true,
 *         mask = {
 *             @MaskField(expression = "email", pattern = MaskPattern.EMAIL_PARTIAL),
 *             @MaskField(expression = "cpf", pattern = MaskPattern.CPF_PARTIAL)
 *         }
 *     )
 *     CustomerDto getCustomer(@PathVariable("id") String id);
 * }
 * }</pre>
 *
 * <h3>Exemplo de Uso com Fallback Automático:</h3>
 * <pre>{@code
 * // O target é resolvido automaticamente como "BillingClient" a partir da interface
 * @FeignClient(name = "billing-service")
 * public interface BillingClient {
 *
 *     @PostMapping("/invoices")
 *     @LogLeg // target assume "BillingClient", type assume OUTBOUND
 *     InvoiceResponse emitInvoice(@RequestBody InvoiceRequest request);
 * }
 * }</pre>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see LegType
 * @see LegPhase
 * @see MaskField
 * @see MaskPattern
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LogLeg {

    /**
     * Identificador canônico do componente, serviço ou recurso externo alvo desta perna de comunicação.
     *
     * <h4>Para que serve:</h4>
     * <ul>
     *   <li><b>Identificação Semântica da Integração:</b> Define claramente qual sistema parceiro,
     *       microsserviço, broker ou gateway está sendo acessado (ex: {@code "billing-service"},
     *       {@code "cielo-gateway"}, {@code "customer-api"}, {@code "kafka-orders-topic"}).</li>
     *   <li><b>Injeção no MDC (SLF4J):</b> O valor configurado aqui é injetado diretamente na chave
     *       {@code leg_target} do MDC da thread durante toda a execução da perna. Qualquer log gerado
     *       dentro do fluxo da chamada (inclusive logs internos do Feign ou WebClient) herdará
     *       automaticamente essa chave.</li>
     *   <li><b>Estruturação no Logger Forense:</b> No evento JSON emitido para o logger {@code AUDIT_LEG_LOGGER},
     *       o campo {@code "target"} recebe exatamente este valor, permitindo auditorias forenses precisas.</li>
     *   <li><b>Filtros e Indexação em Ferramentas de Observabilidade:</b> É a dimensão primária de busca
     *       em ferramentas de agregação de logs como Grafana Loki, Datadog Log Management e Elasticsearch/Kibana.
     *       Permite consultas como:
     *       <pre>{@code
     *       // Grafana Loki:
     *       {app="order-service"} |= "AUDIT_LEG" | json | leg_target="payment-gateway"
     *
     *       // Datadog:
     *       service:order-service @leg_target:payment-gateway @leg_status:FAILED
     *       }</pre>
     *   </li>
     * </ul>
     *
     * <h4>Resolução Dinâmica e Fallback (quando omitido):</h4>
     * Se este atributo for deixado em branco (o padrão {@code ""}), o interceptor do Starter realiza
     * a resolução dinâmica inteligente da seguinte forma:
     * <ol>
     *   <li>Identifica a interface declaradora do método interceptado (ex: {@code CustomerClient}) e
     *       utiliza o nome simples da interface.</li>
     *   <li>Caso não seja uma interface, utiliza o nome simples da classe do componente interceptado
     *       ({@code target.getClass().getSimpleName()}).</li>
     * </ol>
     *
     * @return o identificador semântico do serviço de destino, ou string vazia para fallback automático
     */
    String target() default "";

    /**
     * Natureza e direção arquitetural da perna de integração: {@link LegType#OUTBOUND}, {@link LegType#INBOUND},
     * {@link LegType#CONFIG}, {@link LegType#DATABASE}, {@link LegType#MESSAGING}, {@link LegType#CACHE} ou {@link LegType#INTERNAL}.
     *
     * <h4>Para que serve:</h4>
     * <ul>
     *   <li><b>Classificação Arquitetural da Integração:</b> Categoriza semanticamente a natureza do salto:
     *       <ul>
     *         <li>{@link LegType#INBOUND}: Recepção de tráfego externo (Controllers REST, endpoints gRPC).</li>
     *         <li>{@link LegType#OUTBOUND}: Chamadas síncronas para microsserviços e parceiros (OpenFeign, WebClient). (Padrão)</li>
     *         <li>{@link LegType#CONFIG}: Resolução de configurações dinâmicas, remote config (Vault, Consul, Spring Cloud Config) ou Feature Flags.</li>
     *         <li>{@link LegType#DATABASE}: Operações de persistência e consultas a bancos relacionais e NoSQL (JPA, JDBC, Mongo).</li>
     *         <li>{@link LegType#MESSAGING}: Publicação ou consumo em mensageria/streaming assíncrono (Kafka, RabbitMQ, SQS).</li>
     *         <li>{@link LegType#CACHE}: Acesso a caches em memória ou distribuídos (Redis, Memcached, Caffeine).</li>
     *         <li>{@link LegType#INTERNAL}: Processamentos e computações internas críticas em memória.</li>
     *       </ul>
     *   </li>
     *   <li><b>Injeção no MDC:</b> Injetado na chave {@code leg_type} do MDC durante a execução da perna.</li>
     *   <li><b>Estruturação no Logger Forense:</b> Registrado no campo {@code "type"} dos eventos
     *       {@code LEG_REQUEST} e {@code LEG_RESPONSE} do {@code AUDIT_LEG_LOGGER}.</li>
     * </ul>
     *
     * <h4>Padrão:</h4>
     * O valor padrão é {@link LegType#OUTBOUND}.
     *
     * @return a natureza e direção da perna de comunicação
     */
    LegType type() default LegType.OUTBOUND;

    /**
     * Flag de habilitação explícita (opt-in) para serialização e auditoria dos payloads de requisição
     * e resposta no logger forense {@code AUDIT_LEG_LOGGER}.
     *
     * <h4>Para que serve:</h4>
     * Define se os argumentos passados para o método (payload de requisição) e o valor retornado
     * (payload de resposta) devem ser convertidos em JSON e incluídos no evento estruturado de auditoria.
     *
     * <h4>Por que o padrão corporativo é {@code false}:</h4>
     * <ul>
     *   <li><b>Sobrecarga de Performance (Throughput & Latência):</b> A serialização de árvores de objetos
     *       Java complexos em JSON a cada salto de rede consome ciclos de CPU e gera pressão sobre o Garbage Collector (GC).</li>
     *   <li><b>Custos de Ingestão e Armazenamento de Logs:</b> Salvar payloads integrais em ambientes de alta
     *       volumetria (milhares de requisições por segundo) gera gigabytes/terabytes desnecessários de logs em
     *       plataformas como Datadog, Splunk ou Grafana Loki.</li>
     *   <li><b>Privacidade e Conformidade (LGPD / PCI-DSS):</b> Evita o vazamento acidental de dados pessoais
     *       identificáveis (PII) ou credenciais em repositórios de log centralizados.</li>
     * </ul>
     *
     * <h4>Regra de Habilitação Automática por Mascaramento:</h4>
     * Se o atributo {@link #mask()} for fornecido com uma ou mais regras de {@link MaskField}, o Starter
     * infere automaticamente a necessidade de auditar os payloads, ativando a serialização com as devidas
     * máscaras aplicadas, mesmo que {@code includePayload} seja mantido como {@code false}.
     *
     * @return {@code true} se os payloads de requisição e resposta devem ser auditados em log; {@code false} caso contrário
     */
    boolean includePayload() default false;

    /**
     * Regras declarativas de mascaramento e ofuscação de dados sensíveis (LGPD / PCI-DSS)
     * avaliadas sobre os nós dos payloads de requisição e resposta antes da gravação do log.
     *
     * <h4>Para que serve:</h4>
     * Permite especificar de forma granular e declarativa quais campos ou nós dos payloads
     * contêm dados confidenciais e qual o padrão de ofuscação que deve ser aplicado a eles.
     *
     * <h4>Como funciona:</h4>
     * <ul>
     *   <li>O motor de mascaramento ({@code SpelMaskingService}) inspeciona recursivamente os campos
     *       do objeto ou árvore JSON.</li>
     *   <li>Para cada regra declarada em {@link MaskField}, localiza o campo pelo nome ou expressão SpEL
     *       e substitui o valor original pelo padrão selecionado em {@link MaskPattern}
     *       (ex: {@link MaskPattern#CARD_PARTIAL} para cartões, {@link MaskPattern#CPF_PARTIAL} para CPFs,
     *       {@link MaskPattern#PASSWORD} para senhas/tokens, ou {@link MaskPattern#EMAIL_PARTIAL} para e-mails).</li>
     *   <li>Suporta também máscaras textuais customizadas através do atributo {@link MaskField#customMask()}.</li>
     * </ul>
     *
     * <h4>Exemplo:</h4>
     * <pre>{@code
     * @LogLeg(
     *     target = "payment-gateway",
     *     includePayload = true,
     *     mask = {
     *         @MaskField(expression = "cardNumber", pattern = MaskPattern.CARD_PARTIAL),
     *         @MaskField(expression = "securityCode", pattern = MaskPattern.PASSWORD),
     *         @MaskField(expression = "userDocument", pattern = MaskPattern.CPF_PARTIAL)
     *     }
     * )
     * PaymentConfirmation processPayment(PaymentRequest request);
     * }</pre>
     *
     * @return array de anotações {@link MaskField} com as regras de ofuscação a serem aplicadas
     */
    MaskField[] mask() default {};
}
