package com.empresa.platform.observability.core.flow;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dimensões canônicas de baixa cardinalidade do Flow (ex: variant, feature, experiment).
 * Permite segmentar métricas e correlacionar rotas de migração operacional (ex: legacy vs new).
 */
public class FlowDimensions {

    public static final String VARIANT_KEY = "variant";
    public static final String FEATURE_KEY = "feature";
    public static final String EXPERIMENT_KEY = "experiment";

    private final Map<String, String> dimensions = new ConcurrentHashMap<>();

    public FlowDimensions() {
    }

    public FlowDimensions(String variant) {
        setVariant(variant);
    }

    public FlowDimensions(String variant, String feature) {
        setVariant(variant);
        setFeature(feature);
    }

    public FlowDimensions(String variant, String feature, String experiment) {
        setVariant(variant);
        setFeature(feature);
        setExperiment(experiment);
    }

    public FlowDimensions(Map<String, String> initial) {
        if (initial != null) {
            initial.forEach(this::setDimension);
        }
    }

    public FlowDimensions setDimension(String key, String value) {
        if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
            dimensions.put(key.trim(), value.trim());
        }
        return this;
    }

    public String getDimension(String key) {
        return key != null ? dimensions.get(key.trim()) : null;
    }

    public FlowDimensions setVariant(String variant) {
        return setDimension(VARIANT_KEY, variant);
    }

    public String getVariant() {
        return dimensions.get(VARIANT_KEY);
    }

    public FlowDimensions setFeature(String feature) {
        return setDimension(FEATURE_KEY, feature);
    }

    public String getFeature() {
        return dimensions.get(FEATURE_KEY);
    }

    public FlowDimensions setExperiment(String experiment) {
        return setDimension(EXPERIMENT_KEY, experiment);
    }

    public String getExperiment() {
        return dimensions.get(EXPERIMENT_KEY);
    }

    public boolean isEmpty() {
        return dimensions.isEmpty();
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(dimensions);
    }

    public Tags toTags() {
        if (dimensions.isEmpty()) {
            return Tags.empty();
        }
        Tags tags = Tags.empty();
        for (Map.Entry<String, String> entry : dimensions.entrySet()) {
            tags = tags.and(Tag.of(entry.getKey(), entry.getValue()));
        }
        return tags;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FlowDimensions that = (FlowDimensions) o;
        return Objects.equals(dimensions, that.dimensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimensions);
    }

    @Override
    public String toString() {
        return dimensions.toString();
    }
}
