package com.empresa.platform.observability.core.flow;

import java.util.Collections;
import java.util.Map;

/**
 * Encapsulates the resolved variant and optional low-cardinality metadata for an execution path.
 */
public record FlowVariant(String name, Map<String, String> attributes) {

    public FlowVariant(String name) {
        this(name, Collections.emptyMap());
    }

    public static FlowVariant of(String name) {
        return new FlowVariant(name);
    }
}
