package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.annotation.MDC;
import com.empresa.platform.observability.core.annotation.MDCs;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Aspecto AOP dedicado com responsabilidade única de enriquecer o Mapped Diagnostic Context (MDC)
 * do SLF4J a partir de anotações declarativas {@link MDC @MDC}.
 *
 * <p>Provê gerenciamento com semântica de pilha (stack semantics) e garantia de restauração
 * e limpeza no bloco {@code finally}, protegendo threads reutilizadas contra vazamento de contexto.</p>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see MDC
 * @see MDCs
 */
@Aspect
@Order(15)
public class MdcAspect {

    private static final Logger log = LoggerFactory.getLogger(MdcAspect.class);
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(com.empresa.platform.observability.core.annotation.MDC) || " +
            "@annotation(com.empresa.platform.observability.core.annotation.MDCs) || " +
            "execution(* *(.., @com.empresa.platform.observability.core.annotation.MDC (*), ..)) || " +
            "execution(* *(.., @com.empresa.platform.observability.core.annotation.MDCs (*), ..))")
    public Object processMdcTags(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        if (joinPoint.getTarget() != null) {
            method = AopUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());
        }
        Object[] args = joinPoint.getArgs();
        String[] paramNames = signature.getParameterNames();

        StandardEvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null && args != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }

        Map<String, String> previousMdcValues = new HashMap<>();

        try {
            // 1. Processa anotações @MDC em nível de parâmetro
            Annotation[][] paramAnnotations = method.getParameterAnnotations();
            if (args != null) {
                for (int i = 0; i < paramAnnotations.length && i < args.length; i++) {
                    Object argVal = args[i];
                    for (Annotation ann : paramAnnotations[i]) {
                        if (ann instanceof MDC mdcAnn) {
                            processParamMdc(context, mdcAnn, argVal, previousMdcValues);
                        } else if (ann instanceof MDCs mdcsAnn) {
                            for (MDC mdcAnn : mdcsAnn.value()) {
                                processParamMdc(context, mdcAnn, argVal, previousMdcValues);
                            }
                        }
                    }
                }
            }

            // 2. Processa anotações @MDC em nível de método (pré-execução, sem #result)
            MDC[] methodTags = method.getAnnotationsByType(MDC.class);
            for (MDC mdcAnn : methodTags) {
                String expr = mdcAnn.expression();
                if (expr == null || !expr.contains("#result")) {
                    processMethodMdc(context, mdcAnn, previousMdcValues);
                }
            }

            Object result;
            try {
                result = joinPoint.proceed();
            } catch (Throwable t) {
                context.setVariable("error", t);
                throw t;
            }

            // 3. Processa anotações @MDC pós-execução (com #result)
            context.setVariable("result", result);
            for (MDC mdcAnn : methodTags) {
                String expr = mdcAnn.expression();
                if (expr != null && expr.contains("#result")) {
                    processMethodMdc(context, mdcAnn, previousMdcValues);
                }
            }

            return result;
        } finally {
            // Restaura o estado anterior do MDC com segurança de pilha
            for (Map.Entry<String, String> entry : previousMdcValues.entrySet()) {
                if (entry.getValue() != null) {
                    org.slf4j.MDC.put(entry.getKey(), entry.getValue());
                } else {
                    org.slf4j.MDC.remove(entry.getKey());
                }
            }
        }
    }

    private void processParamMdc(EvaluationContext context,
                                 MDC mdcAnn,
                                 Object argVal,
                                 Map<String, String> previousMdcValues) {
        String key = resolveKey(mdcAnn);
        if (key == null || key.isBlank()) {
            return;
        }

        try {
            String value;
            String expr = mdcAnn.expression();
            if (expr != null && !expr.isBlank()) {
                value = parser.parseExpression(expr).getValue(context, String.class);
            } else {
                String staticVal = resolveStaticValue(mdcAnn);
                value = staticVal != null ? staticVal : (argVal != null ? String.valueOf(argVal) : null);
            }

            if (value != null) {
                if (!previousMdcValues.containsKey(key)) {
                    previousMdcValues.put(key, org.slf4j.MDC.get(key));
                }
                org.slf4j.MDC.put(key, value);
            }
        } catch (Exception e) {
            log.warn("Falha ao avaliar SpEL no parâmetro para chave MDC [{}]: {}", key, e.getMessage());
        }
    }

    private void processMethodMdc(EvaluationContext context,
                                  MDC mdcAnn,
                                  Map<String, String> previousMdcValues) {
        String key = resolveKey(mdcAnn);
        if (key == null || key.isBlank()) {
            return;
        }

        String expr = mdcAnn.expression();
        try {
            String value = null;
            if (expr != null && !expr.isBlank()) {
                value = parser.parseExpression(expr).getValue(context, String.class);
            } else {
                value = resolveStaticValue(mdcAnn);
            }

            if (value != null) {
                if (!previousMdcValues.containsKey(key)) {
                    previousMdcValues.put(key, org.slf4j.MDC.get(key));
                }
                org.slf4j.MDC.put(key, value);
            }
        } catch (Exception e) {
            log.warn("Falha ao avaliar SpEL [{}] para chave MDC [{}]: {}", expr, key, e.getMessage());
        }
    }

    private String resolveKey(MDC mdcAnn) {
        if (mdcAnn.key() != null && !mdcAnn.key().isBlank()) {
            return mdcAnn.key();
        }
        if (mdcAnn.name() != null && !mdcAnn.name().isBlank()) {
            return mdcAnn.name();
        }
        return mdcAnn.value();
    }

    private String resolveStaticValue(MDC mdcAnn) {
        if ((mdcAnn.key() != null && !mdcAnn.key().isBlank()) ||
            (mdcAnn.name() != null && !mdcAnn.name().isBlank())) {
            if (mdcAnn.value() != null && !mdcAnn.value().isBlank()) {
                return mdcAnn.value();
            }
        }
        return null;
    }
}
