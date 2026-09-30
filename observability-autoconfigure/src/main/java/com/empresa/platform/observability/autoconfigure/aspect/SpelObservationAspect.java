package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.annotation.ObservationTag;
import com.empresa.platform.observability.core.annotation.ObservationTags;
import com.empresa.platform.observability.core.cardinality.CardinalityPolicy;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aspecto AOP dedicado com responsabilidade exclusiva de enriquecer o contexto do Micrometer Observation
 * (Spans de Tracing e Séries Temporais de Métricas) a partir de anotações {@link ObservationTag @ObservationTag}.
 *
 * <p>Suporta valores literais fixos ({@link ObservationTag#value()}) e extração dinâmica via SpEL
 * ({@link ObservationTag#expression()}), em nível de método, classe ou parâmetro.</p>
 *
 * @author Platform Architecture Team
 * @since 1.0.0
 * @see ObservationTag
 * @see ObservationTags
 */
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
            "@within(com.empresa.platform.observability.core.annotation.ObservationTag) || " +
            "@within(com.empresa.platform.observability.core.annotation.ObservationTags) || " +
            "execution(* *(.., @com.empresa.platform.observability.core.annotation.ObservationTag (*), ..)) || " +
            "execution(* *(.., @com.empresa.platform.observability.core.annotation.ObservationTags (*), ..))")
    public Object processObservationTags(ProceedingJoinPoint joinPoint) throws Throwable {
        Observation observation = observationRegistry != null ? observationRegistry.getCurrentObservation() : null;
        if (observation == null) {
            return joinPoint.proceed();
        }

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

        // 1. Processa anotações @ObservationTag em nível de parâmetro
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        if (args != null) {
            for (int i = 0; i < paramAnnotations.length && i < args.length; i++) {
                Object argVal = args[i];
                for (Annotation ann : paramAnnotations[i]) {
                    if (ann instanceof ObservationTag tag) {
                        processParamTag(observation, context, tag, argVal);
                    } else if (ann instanceof ObservationTags tagsContainer) {
                        for (ObservationTag tag : tagsContainer.value()) {
                            processParamTag(observation, context, tag, argVal);
                        }
                    }
                }
            }
        }

        // 2. Coleta anotações @ObservationTag em nível de classe e método
        List<ObservationTag> tags = new ArrayList<>();
        Class<?> targetClass = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass() : method.getDeclaringClass();
        Collections.addAll(tags, targetClass.getAnnotationsByType(ObservationTag.class));
        if (targetClass != method.getDeclaringClass()) {
            Collections.addAll(tags, method.getDeclaringClass().getAnnotationsByType(ObservationTag.class));
        }
        Collections.addAll(tags, method.getAnnotationsByType(ObservationTag.class));
        if (signature.getMethod() != method) {
            for (ObservationTag it : signature.getMethod().getAnnotationsByType(ObservationTag.class)) {
                if (!tags.contains(it)) {
                    tags.add(it);
                }
            }
        }

        // 3. Processa tags pré-execução (valores fixos ou expressões sem #result)
        for (ObservationTag tag : tags) {
            boolean hasFixedValue = tag.value() != null && !tag.value().isBlank();
            boolean hasResultInExpr = tag.expression() != null && tag.expression().contains("#result");
            if (hasFixedValue || !hasResultInExpr) {
                processTag(observation, context, tag);
            }
        }

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable t) {
            context.setVariable("error", t);
            throw t;
        }

        // 4. Processa tags pós-execução (expressões dinâmicas contendo #result)
        context.setVariable("result", result);
        for (ObservationTag tag : tags) {
            boolean hasFixedValue = tag.value() != null && !tag.value().isBlank();
            boolean hasResultInExpr = tag.expression() != null && tag.expression().contains("#result");
            if (!hasFixedValue && hasResultInExpr) {
                processTag(observation, context, tag);
            }
        }

        return result;
    }

    private void processTag(Observation observation, EvaluationContext context, ObservationTag tag) {
        String key = tag.key();
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            String value = null;
            if (tag.value() != null && !tag.value().isBlank()) {
                value = tag.value();
            } else if (tag.expression() != null && !tag.expression().isBlank()) {
                value = parser.parseExpression(tag.expression()).getValue(context, String.class);
            }

            applyTagToObservation(observation, tag, value);
        } catch (Exception e) {
            log.warn("Falha ao avaliar ObservationTag para chave [{}]: {}", key, e.getMessage());
        }
    }

    private void processParamTag(Observation observation, EvaluationContext context, ObservationTag tag, Object argVal) {
        String key = tag.key();
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            String value;
            if (tag.value() != null && !tag.value().isBlank()) {
                value = tag.value();
            } else if (tag.expression() != null && !tag.expression().isBlank()) {
                value = parser.parseExpression(tag.expression()).getValue(context, String.class);
            } else {
                value = argVal != null ? String.valueOf(argVal) : null;
            }

            applyTagToObservation(observation, tag, value);
        } catch (Exception e) {
            log.warn("Falha ao avaliar ObservationTag no parâmetro para chave [{}]: {}", key, e.getMessage());
        }
    }

    private void applyTagToObservation(Observation observation, ObservationTag tag, String value) {
        if (value == null) {
            return;
        }
        boolean permittedForMetric = tag.lowCardinality() && !tag.highCardinality()
                && CardinalityPolicy.isPermittedMetricTag(tag.key());
        if (permittedForMetric) {
            observation.lowCardinalityKeyValue(tag.key(), value);
        } else {
            // Downgrade seguro para tag de tracing/span (Spec 41)
            observation.highCardinalityKeyValue(tag.key(), value);
        }
    }
}
