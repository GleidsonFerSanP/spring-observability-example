package com.empresa.platform.observability.core.flow;

import java.util.Optional;

/**
 * SPI for resolving active flow variant (e.g. from feature flags or context).
 */
@FunctionalInterface
public interface FlowVariantProvider {

    Optional<FlowVariant> resolve();
}
