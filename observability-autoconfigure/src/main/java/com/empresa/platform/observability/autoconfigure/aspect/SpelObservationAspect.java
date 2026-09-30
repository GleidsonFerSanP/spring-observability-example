package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.annotation.ObservationTag;
import com.empresa.platform.observability.core.annotation.ObservationTags;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Order(20)
public class SpelObservationAspect {

    private static final Logger log = LoggerFactory.getLogger(SpelObservationAspect.class);
    private final ObservationRegistry observationRegistry;
    private final ExpressionParser parser = new SpelExpressionParser();

    public SpelObservationAspect(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Around("@annotation(com.empresa.platform.observability.core.annotation.ObservationTag) || " +
            "@annotation(com.empresa.platform.observability.core.annotation.ObservationTags) || " +
            "execution(* *(.., @com.empresa.platform.observability.core.annotation.ObservationTag (*), ..)) || " +
            "execution(* *(.., @com.empresa.platform.observability.core.annotation.ObservationTags (*), ..))")
    public Object processObservationTags(ProceedingJoinPoint joinPoint) throws Throwable {
        Observation observation = observationRegistry != null ? observationRegistry.getCurrentObservation() : null;

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
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
            // 1. Process parameter-level @ObservationTag annotations
            Annotation[][] paramAnnotations = method.getParameterAnnotations();
            if (args != null) {
                for (int i = 0; i < paramAnnotations.length && i < args.length; i++) {
                    Object argVal = args[i];
                    for (Annotation ann : paramAnnotations[i]) {
                        if (ann instanceof ObservationTag tag) {
                            evaluateParameterTag(observation, context, tag, argVal, previousMdcValues);
                        } else if (ann instanceof ObservationTags tags) {
                            for (ObservationTag tag : tags.value()) {
                                evaluateParameterTag(observation, context, tag, argVal, previousMdcValues);
                            }
                        }
                    }
                }
            }

            // 2. Process method-level @ObservationTag annotations (pre-execution, no #result)
            ObservationTag[] tags = method.getAnnotationsByType(ObservationTag.class);
            for (ObservationTag tag : tags) {
                if (tag.expression() == null || !tag.expression().contains("#result")) {
                    evaluateAndTag(observation, context, tag, previousMdcValues);
                }
            }

            Object result;
            try {
                result = joinPoint.proceed();
            } catch (Throwable t) {
                context.setVariable("error", t);
                throw t;
            }

            // 3. Process post-execution tags (with #result)
            context.setVariable("result", result);
            for (ObservationTag tag : tags) {
                if (tag.expression() != null && tag.expression().contains("#result")) {
                    evaluateAndTag(observation, context, tag, previousMdcValues);
                }
            }

            return result;
        } finally {
            // Restore previous MDC state (or remove if key had no previous value)
            for (Map.Entry<String, String> entry : previousMdcValues.entrySet()) {
                if (entry.getValue() != null) {
                    MDC.put(entry.getKey(), entry.getValue());
                } else {
                    MDC.remove(entry.getKey());
                }
            }
        }
    }

    private void evaluateParameterTag(Observation observation,
                                      EvaluationContext context,
                                      ObservationTag tag,
                                      Object argVal,
                                      Map<String, String> previousMdcValues) {
        try {
            String value;
            if (tag.expression() == null || tag.expression().isBlank()) {
                value = argVal != null ? String.valueOf(argVal) : null;
            } else {
                value = parser.parseExpression(tag.expression()).getValue(context, String.class);
            }
            applyTag(observation, tag, value, previousMdcValues);
        } catch (Exception e) {
            log.warn("Failed to evaluate parameter tag [{}]: {}", tag.key(), e.getMessage());
        }
    }

    private void evaluateAndTag(Observation observation,
                                EvaluationContext context,
                                ObservationTag tag,
                                Map<String, String> previousMdcValues) {
        try {
            String expr = tag.expression();
            if (expr == null || expr.isBlank()) {
                return;
            }
            String value = parser.parseExpression(expr).getValue(context, String.class);
            applyTag(observation, tag, value, previousMdcValues);
        } catch (Exception e) {
            log.warn("Failed to evaluate SpEL expression [{}] for Observation tag [{}]: {}", tag.expression(), tag.key(), e.getMessage());
        }
    }

    private void applyTag(Observation observation,
                          ObservationTag tag,
                          String value,
                          Map<String, String> previousMdcValues) {
        if (value == null) {
            return;
        }

        // Bridge to SLF4J MDC if enabled
        if (tag.mdc()) {
            if (!previousMdcValues.containsKey(tag.key())) {
                previousMdcValues.put(tag.key(), MDC.get(tag.key()));
            }
            MDC.put(tag.key(), value);
        }

        // Bridge to Micrometer Observation if an active observation exists
        if (observation != null) {
            boolean permittedForMetric = tag.lowCardinality() && !tag.highCardinality()
                    && com.empresa.platform.observability.core.cardinality.CardinalityPolicy.isPermittedMetricTag(tag.key());
            if (permittedForMetric) {
                observation.lowCardinalityKeyValue(tag.key(), value);
            } else {
                observation.highCardinalityKeyValue(tag.key(), value);
            }
        }
    }
}
