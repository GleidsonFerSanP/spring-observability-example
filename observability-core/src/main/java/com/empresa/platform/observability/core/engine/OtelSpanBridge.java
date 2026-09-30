package com.empresa.platform.observability.core.engine;

import java.lang.reflect.Method;

/**
 * Ponte segura e não-intrusiva com a OpenTelemetry API.
 * Permite injetar atributos diretamente no Span ativo se a biblioteca ou o agente (ex: dd-java-agent com DD_TRACE_OTEL_ENABLED=true)
 * estiverem presentes no classpath em tempo de execução, sem impor dependência de compilação obrigatória.
 */
public final class OtelSpanBridge {

    private static final boolean OTEL_AVAILABLE;
    private static Method currentSpanMethod;
    private static Method setAttributeMethod;

    static {
        boolean available = false;
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            currentSpanMethod = spanClass.getMethod("current");
            setAttributeMethod = spanClass.getMethod("setAttribute", String.class, String.class);
            available = true;
        } catch (Throwable ignored) {
            currentSpanMethod = null;
            setAttributeMethod = null;
        }
        OTEL_AVAILABLE = available;
    }

    private OtelSpanBridge() {
    }

    public static boolean isAvailable() {
        return OTEL_AVAILABLE;
    }

    public static void setAttribute(String key, String value) {
        if (!OTEL_AVAILABLE || key == null || value == null || currentSpanMethod == null || setAttributeMethod == null) {
            return;
        }
        try {
            Object span = currentSpanMethod.invoke(null);
            if (span != null) {
                setAttributeMethod.invoke(span, key, value);
            }
        } catch (Throwable ignored) {
        }
    }
}
