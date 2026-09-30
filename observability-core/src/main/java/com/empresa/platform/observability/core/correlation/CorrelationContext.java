package com.empresa.platform.observability.core.correlation;

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
        }
    }

    private CorrelationContext() {
    }

    public static String getCorrelationId() {
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
        } catch (Throwable ignored) {
        }

        String correlationId = MDC.get(CORRELATION_ID_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }

        return null;
    }

    public static void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put(CORRELATION_ID_KEY, correlationId);
        }
    }

    public static void clear() {
        MDC.remove(CORRELATION_ID_KEY);
    }

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
