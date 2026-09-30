package com.empresa.platform.observability.autoconfigure.actuator;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.*;

/**
 * Metric catalog endpoint: /actuator/observabilitymetrics
 * (Spec Section 38)
 */
@Endpoint(id = "observabilitymetrics")
public class ObservabilityMetricsEndpoint {

    private final Collection<MeterRegistry> registries;

    public ObservabilityMetricsEndpoint(Collection<MeterRegistry> registries) {
        this.registries = registries;
    }

    @ReadOperation
    public Map<String, Object> listMetrics() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, Object>> metricsList = new ArrayList<>();

        Set<String> processedNames = new HashSet<>();

        for (MeterRegistry registry : registries) {
            for (Meter meter : registry.getMeters()) {
                String name = meter.getId().getName();
                if (processedNames.add(name)) {
                    Map<String, Object> meterInfo = new LinkedHashMap<>();
                    meterInfo.put("name", name);
                    meterInfo.put("type", meter.getId().getType().name());
                    meterInfo.put("description", meter.getId().getDescription() != null ? meter.getId().getDescription() : "");

                    List<String> tagKeys = new ArrayList<>();
                    for (Tag tag : meter.getId().getTags()) {
                        tagKeys.add(tag.getKey());
                    }
                    meterInfo.put("tagKeys", tagKeys);
                    metricsList.add(meterInfo);
                }
            }
        }

        response.put("count", metricsList.size());
        response.put("metrics", metricsList);
        return response;
    }
}
