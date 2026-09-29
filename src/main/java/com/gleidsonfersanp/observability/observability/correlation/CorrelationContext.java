package com.gleidsonfersanp.observability.observability.correlation;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Contexto utilitário para gerenciamento do Correlation ID (cid) no MDC do SLF4J
 * e propagação entre fronteiras síncronas (HTTP), assíncronas (Kafka, SQS) e threads de worker.
 */
public final class CorrelationContext {

    public static final String CORRELATION_ID_KEY = "correlation_id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    static {
        // Registra o accessor no ContextRegistry do Micrometer para propagação automática
        // de contexto em bibliotecas assíncronas e thread pools
        try {
            ContextRegistry.getInstance().registerThreadLocalAccessor(new ThreadLocalAccessor<String>() {
                @Override
                public Object key() {
                    return CORRELATION_ID_KEY;
                }

                @Override
                public String getValue() {
                    return CorrelationContext.getCorrelationId();
                }

                @Override
                public void setValue(String value) {
                    if (value != null && !value.isBlank()) {
                        MDC.put(CORRELATION_ID_KEY, value);
                    }
                }

                @Override
                public void reset() {
                    MDC.remove(CORRELATION_ID_KEY);
                }
            });
        } catch (Exception ignored) {
            // Caso já registrado
        }
    }

    private CorrelationContext() {
        // Utility class
    }

    /**
     * Retorna o correlation_id atual.
     * Em contexto HTTP, os atributos da requisição corrente são autoritativos para evitar
     * contaminação entre threads reutilizadas em pools (ex.: Circuit Breaker TimeLimiter).
     */
    public static String getCorrelationId() {
        // 1. Se estiver em contexto HTTP, o atributo da requisição ativa é autoritativo
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                HttpServletRequest req = servletAttributes.getRequest();
                Object attr = req.getAttribute(CORRELATION_ID_KEY);
                if (attr != null) {
                    String correlationId = attr.toString();
                    MDC.put(CORRELATION_ID_KEY, correlationId);
                    return correlationId;
                }
                String header = req.getHeader(CORRELATION_ID_HEADER);
                if (header != null && !header.isBlank()) {
                    MDC.put(CORRELATION_ID_KEY, header);
                    return header;
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Fallback para MDC (mensageria assíncrona Kafka/SQS ou jobs agendados)
        String correlationId = MDC.get(CORRELATION_ID_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }

        return null;
    }

    /**
     * Define explicitamente o correlation_id no MDC.
     */
    public static void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(CORRELATION_ID_KEY, correlationId);
        }
    }

    /**
     * Remove o correlation_id do MDC.
     */
    public static void clear() {
        MDC.remove(CORRELATION_ID_KEY);
    }

    /**
     * Obtém o correlation_id existente no contexto ou utiliza o traceId (se disponível),
     * gerando um novo UUID se nenhum estiver presente.
     */
    public static String generateOrGet() {
        String correlationId = getCorrelationId();
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }

        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            correlationId = traceId;
        } else {
            correlationId = UUID.randomUUID().toString();
        }

        setCorrelationId(correlationId);
        return correlationId;
    }

    /**
     * Executa uma ação em um bloco com correlation_id garantido no MDC, limpando-o ao final.
     */
    public static void runWithCorrelationId(String correlationId, Runnable runnable) {
        String previous = getCorrelationId();
        try {
            setCorrelationId(correlationId != null && !correlationId.isBlank() ? correlationId : generateOrGet());
            runnable.run();
        } finally {
            if (previous != null) {
                setCorrelationId(previous);
            } else {
                clear();
            }
        }
    }

    /**
     * Executa um supplier em um bloco com correlation_id garantido no MDC, limpando-o ao final.
     */
    public static <T> T supplyWithCorrelationId(String correlationId, Supplier<T> supplier) {
        String previous = getCorrelationId();
        try {
            setCorrelationId(correlationId != null && !correlationId.isBlank() ? correlationId : generateOrGet());
            return supplier.get();
        } finally {
            if (previous != null) {
                setCorrelationId(previous);
            } else {
                clear();
            }
        }
    }
}
