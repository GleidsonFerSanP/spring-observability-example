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
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

/**
 * Aspecto AOP dedicado com responsabilidade exclusiva de enriquecer o contexto do Micrometer Observation
 * (Spans de Tracing e Séries Temporais de Métricas) a partir de anotações {@link ObservationTag @ObservationTag}.
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

    @Around("@annotation(com.empresa.platform.observability.core.annotation.ObservationTag) || @annotation(com.empresa.platform.observability.core.annotation.ObservationTags)")
    public Object processObservationTags(ProceedingJoinPoint joinPoint) throws Throwable {
        Observation observation = observationRegistry != null ? observationRegistry.getCurrentObservation() : null;
        if (observation == null) {
            return joinPoint.proceed();
        }

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

        ObservationTag[] tags = method.getAnnotationsByType(ObservationTag.class);

        for (ObservationTag tag : tags) {
            if (tag.expression() == null || !tag.expression().contains("#result")) {
                evaluateAndTag(observation, context, tag);
            }
        }

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable t) {
            context.setVariable("error", t);
            throw t;
        }

        context.setVariable("result", result);
        for (ObservationTag tag : tags) {
            if (tag.expression() != null && tag.expression().contains("#result")) {
                evaluateAndTag(observation, context, tag);
            }
        }

        return result;
    }

    private void evaluateAndTag(Observation observation, EvaluationContext context, ObservationTag tag) {
        try {
            String expr = tag.expression();
            if (expr == null || expr.isBlank()) {
                return;
            }
            String value = parser.parseExpression(expr).getValue(context, String.class);
            if (value != null) {
                boolean permittedForMetric = tag.lowCardinality() && !tag.highCardinality()
                        && com.empresa.platform.observability.core.cardinality.CardinalityPolicy.isPermittedMetricTag(tag.key());
                if (permittedForMetric) {
                    observation.lowCardinalityKeyValue(tag.key(), value);
                } else {
                    // Downgrade seguro para tag de tracing/span (Spec 41)
                    observation.highCardinalityKeyValue(tag.key(), value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to evaluate SpEL expression [{}] for Observation tag [{}]: {}", tag.expression(), tag.key(), e.getMessage());
        }
    }
}
