package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.annotation.LegPhase;
import com.empresa.platform.observability.core.annotation.LegType;
import com.empresa.platform.observability.core.annotation.LogLeg;
import com.empresa.platform.observability.core.leg.LegContext;
import com.empresa.platform.observability.core.leg.SpelMaskingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

@Aspect
@Order(2)
public class LegLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger("AUDIT_LEG_LOGGER");

    private final SpelMaskingService maskingService;
    private final ObjectMapper objectMapper;

    public LegLoggingAspect(SpelMaskingService maskingService, ObjectMapper objectMapper) {
        this.maskingService = maskingService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Around("@annotation(logLeg) || @within(logLeg)")
    public Object traceLeg(ProceedingJoinPoint joinPoint, LogLeg logLeg) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        if (logLeg == null) {
            logLeg = method.getAnnotation(LogLeg.class);
            if (logLeg == null) {
                logLeg = joinPoint.getTarget().getClass().getAnnotation(LogLeg.class);
            }
        }

        String target = resolveTarget(logLeg, joinPoint);
        LegType type = logLeg != null ? logLeg.type() : LegType.OUTBOUND;

        LegContext.LegSnapshot snapshot = LegContext.startLeg(target, type);
        setupMdc(snapshot, LegPhase.REQUEST);

        long startNanos = snapshot.startNanos();
        Object[] args = joinPoint.getArgs();

        try {
            boolean shouldIncludePayload = (logLeg != null && (logLeg.includePayload() || (logLeg.mask() != null && logLeg.mask().length > 0)));
            JsonNode maskedRequest = null;
            if (shouldIncludePayload) {
                Object requestPayload = (args.length == 1) ? args[0] : args;
                maskedRequest = maskingService.maskPayload(requestPayload, method, args, null, logLeg != null ? logLeg.mask() : null);
            }

            ObjectNode logPayload = objectMapper.createObjectNode();
            logPayload.put("event", "LEG_REQUEST");
            logPayload.put("legNumber", snapshot.legNumber());
            if (snapshot.parentLegNumber() != null) {
                logPayload.put("parentLeg", snapshot.parentLegNumber());
            }
            logPayload.put("type", snapshot.type().name());
            logPayload.put("phase", LegPhase.REQUEST.name());
            logPayload.put("target", snapshot.target());
            logPayload.put("method", method.getName());
            if (maskedRequest != null) {
                logPayload.set("request", maskedRequest);
            }

            log.info("[LEG {}][{}][REQUEST] Invocando {}.{}(): {}",
                    snapshot.legNumber(), snapshot.type(), snapshot.target(), method.getName(), logPayload);

        } catch (Exception e) {
            log.debug("Falha ao registrar log de request da Leg: {}", e.getMessage());
        }

        Object result;
        try {
            result = joinPoint.proceed();

            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            setupMdc(snapshot, LegPhase.RESPONSE);
            MDC.put("leg_duration_ms", String.valueOf(durationMs));
            MDC.put("leg_status", "SUCCESS");

            boolean shouldIncludePayload = (logLeg != null && (logLeg.includePayload() || (logLeg.mask() != null && logLeg.mask().length > 0)));
            JsonNode maskedResponse = null;
            if (shouldIncludePayload) {
                maskedResponse = maskingService.maskPayload(result, method, args, result, logLeg != null ? logLeg.mask() : null);
            }

            ObjectNode logPayload = objectMapper.createObjectNode();
            logPayload.put("event", "LEG_RESPONSE");
            logPayload.put("legNumber", snapshot.legNumber());
            if (snapshot.parentLegNumber() != null) {
                logPayload.put("parentLeg", snapshot.parentLegNumber());
            }
            logPayload.put("type", snapshot.type().name());
            logPayload.put("phase", LegPhase.RESPONSE.name());
            logPayload.put("target", snapshot.target());
            logPayload.put("status", "SUCCESS");
            logPayload.put("durationMs", durationMs);
            if (maskedResponse != null) {
                logPayload.set("response", maskedResponse);
            }

            log.info("[LEG {}][{}][RESPONSE] {} respondeu com sucesso em {}ms: {}",
                    snapshot.legNumber(), snapshot.type(), snapshot.target(), durationMs, logPayload);

            return result;

        } catch (Throwable t) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            setupMdc(snapshot, LegPhase.RESPONSE);
            MDC.put("leg_duration_ms", String.valueOf(durationMs));
            MDC.put("leg_status", "FAILED");

            ObjectNode logPayload = objectMapper.createObjectNode();
            logPayload.put("event", "LEG_RESPONSE");
            logPayload.put("legNumber", snapshot.legNumber());
            if (snapshot.parentLegNumber() != null) {
                logPayload.put("parentLeg", snapshot.parentLegNumber());
            }
            logPayload.put("type", snapshot.type().name());
            logPayload.put("phase", LegPhase.RESPONSE.name());
            logPayload.put("target", snapshot.target());
            logPayload.put("status", "FAILED");
            logPayload.put("durationMs", durationMs);
            logPayload.put("errorType", t.getClass().getSimpleName());
            logPayload.put("errorMessage", t.getMessage());

            log.error("[LEG {}][{}][RESPONSE] {} falhou após {}ms ({}): {}",
                    snapshot.legNumber(), snapshot.type(), snapshot.target(), durationMs, t.getClass().getSimpleName(), logPayload);

            throw t;

        } finally {
            cleanupMdc();
            LegContext.completeLeg();
        }
    }

    private String resolveTarget(LogLeg logLeg, ProceedingJoinPoint joinPoint) {
        if (logLeg != null && !logLeg.target().isBlank()) {
            return logLeg.target();
        }
        if (joinPoint.getSignature() instanceof MethodSignature methodSignature) {
            Class<?> declaringType = methodSignature.getDeclaringType();
            if (declaringType != null && !declaringType.equals(Object.class)) {
                return declaringType.getSimpleName();
            }
        }
        return joinPoint.getTarget().getClass().getSimpleName();
    }

    private void setupMdc(LegContext.LegSnapshot snapshot, LegPhase phase) {
        MDC.put("leg_number", String.valueOf(snapshot.legNumber()));
        if (snapshot.parentLegNumber() != null) {
            MDC.put("leg_parent", String.valueOf(snapshot.parentLegNumber()));
        }
        MDC.put("leg_type", snapshot.type().name());
        MDC.put("leg_target", snapshot.target());
        MDC.put("leg_phase", phase.name());
    }

    private void cleanupMdc() {
        MDC.remove("leg_number");
        MDC.remove("leg_parent");
        MDC.remove("leg_type");
        MDC.remove("leg_target");
        MDC.remove("leg_phase");
        MDC.remove("leg_duration_ms");
        MDC.remove("leg_status");
    }
}
