package com.empresa.platform.observability.core.leg;

import com.empresa.platform.observability.core.annotation.MaskField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;

/**
 * Motor de mascaramento de dados sensíveis para logs de Legs utilizando expressões SpEL
 * e manipulação segura de árvore JSON.
 */
public class SpelMaskingService {

    private static final Logger log = LoggerFactory.getLogger(SpelMaskingService.class);

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ObjectMapper objectMapper;

    public SpelMaskingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode maskPayload(Object targetObject, Method method, Object[] args, Object result, MaskField[] maskFields) {
        if (targetObject == null) {
            return null;
        }

        try {
            JsonNode rootNode = objectMapper.valueToTree(targetObject);

            if (maskFields == null || maskFields.length == 0) {
                return rootNode;
            }

            EvaluationContext context = buildEvaluationContext(method, args, result);

            for (MaskField maskField : maskFields) {
                applyMaskField(rootNode, maskField, context);
            }

            return rootNode;
        } catch (Exception e) {
            log.warn("Falha ao aplicar mascaramento SpEL para log de Leg: {}", e.getMessage());
            try {
                return objectMapper.valueToTree(targetObject);
            } catch (Exception ex) {
                return TextNode.valueOf(String.valueOf(targetObject));
            }
        }
    }

    private void applyMaskField(JsonNode rootNode, MaskField maskField, EvaluationContext context) {
        try {
            String exprStr = maskField.expression();
            Object resolvedValue = null;
            try {
                resolvedValue = parser.parseExpression(exprStr).getValue(context);
            } catch (Exception ignored) {
            }

            String maskedText = resolveMaskedValue(resolvedValue, maskField);
            String propertyName = extractTrailingPropertyName(exprStr);

            maskRecursive(rootNode, propertyName, resolvedValue != null ? String.valueOf(resolvedValue) : null, maskedText);
        } catch (Exception e) {
            log.debug("Erro não-crítico ao processar MaskField '{}': {}", maskField.expression(), e.getMessage());
        }
    }

    private String resolveMaskedValue(Object rawValue, MaskField maskField) {
        if (maskField.customMask() != null && !maskField.customMask().isBlank()) {
            return maskField.customMask();
        }
        String strVal = (rawValue != null) ? String.valueOf(rawValue) : "";
        return maskField.pattern().apply(strVal);
    }

    private void maskRecursive(JsonNode node, String targetKey, String rawValue, String maskedReplacement) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode child = field.getValue();

                boolean matchesKey = targetKey != null && (key.equalsIgnoreCase(targetKey) || key.endsWith(targetKey));
                boolean matchesValue = rawValue != null && child.isTextual() && child.asText().equals(rawValue);

                if (matchesKey || matchesValue) {
                    objectNode.put(key, maskedReplacement);
                } else {
                    maskRecursive(child, targetKey, rawValue, maskedReplacement);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode item = arrayNode.get(i);
                if (rawValue != null && item.isTextual() && item.asText().equals(rawValue)) {
                    arrayNode.set(i, new TextNode(maskedReplacement));
                } else {
                    maskRecursive(item, targetKey, rawValue, maskedReplacement);
                }
            }
        }
    }

    private String extractTrailingPropertyName(String expr) {
        if (expr == null) return null;
        String clean = expr.replaceAll("\\(\\)", "").trim();
        int lastDot = clean.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < clean.length() - 1) {
            return clean.substring(lastDot + 1);
        }
        int lastBracket = clean.lastIndexOf('[');
        if (lastBracket >= 0 && lastBracket < clean.length() - 1) {
            return clean.substring(lastBracket + 1).replace("]", "").replace("'", "").replace("\"", "");
        }
        if (clean.startsWith("#")) {
            return clean.substring(1);
        }
        return clean;
    }

    private EvaluationContext buildEvaluationContext(Method method, Object[] args, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        if (result != null) {
            context.setVariable("result", result);
        }

        if (method != null && args != null) {
            String[] paramNames = paramNameDiscoverer.getParameterNames(method);
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                context.setVariable("arg" + i, arg);
                if (paramNames != null && i < paramNames.length) {
                    context.setVariable(paramNames[i], arg);
                }
                if (i == 0) {
                    context.setVariable("request", arg);
                    context.setVariable("payload", arg);
                }
            }
        }

        return context;
    }
}
