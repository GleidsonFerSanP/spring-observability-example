package com.gleidsonfersanp.observability.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@Order(20)
public class SpelObservationAspect {

    private static final Logger log = LoggerFactory.getLogger(SpelObservationAspect.class);
    private final ObservationRegistry observationRegistry;
    private final ExpressionParser parser = new SpelExpressionParser();

    public SpelObservationAspect(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Around("@annotation(com.gleidsonfersanp.observability.observability.ObservationTag) || @annotation(com.gleidsonfersanp.observability.observability.ObservationTags)")
    public Object processObservationTags(ProceedingJoinPoint joinPoint) throws Throwable {
        Observation observation = observationRegistry.getCurrentObservation();
        if (observation == null) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = signature.getParameterNames();

        StandardEvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }

        ObservationTag[] tags = method.getAnnotationsByType(ObservationTag.class);

        // Pre-evaluation (for parameters, before result is available)
        for (ObservationTag tag : tags) {
            if (!tag.expression().contains("#result")) {
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

        // Post-evaluation (for results)
        context.setVariable("result", result);
        for (ObservationTag tag : tags) {
            if (tag.expression().contains("#result")) {
                evaluateAndTag(observation, context, tag);
            }
        }

        return result;
    }

    private void evaluateAndTag(Observation observation, EvaluationContext context, ObservationTag tag) {
        try {
            String value = parser.parseExpression(tag.expression()).getValue(context, String.class);
            if (value != null) {
                if (tag.highCardinality()) {
                    observation.highCardinalityKeyValue(tag.key(), value);
                } else {
                    observation.lowCardinalityKeyValue(tag.key(), value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to evaluate SpEL expression [{}] for Observation tag [{}]: {}", tag.expression(), tag.key(), e.getMessage());
        }
    }
}
