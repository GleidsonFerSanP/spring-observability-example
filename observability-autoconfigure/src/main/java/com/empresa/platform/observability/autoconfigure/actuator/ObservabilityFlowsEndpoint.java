package com.empresa.platform.observability.autoconfigure.actuator;

import com.empresa.platform.observability.core.annotation.TrackFlow;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Dynamic Flow Discovery actuator endpoint: /actuator/observabilityflows
 * (Spec Section 39)
 */
@Endpoint(id = "observabilityflows")
public class ObservabilityFlowsEndpoint {

    private final ApplicationContext applicationContext;

    public ObservabilityFlowsEndpoint(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @ReadOperation
    public Map<String, Object> discoverFlows() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> discoveredFlows = new ArrayList<>();

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> targetClass = bean.getClass();

                // Check class-level @TrackFlow
                TrackFlow classTrackFlow = AnnotationUtils.findAnnotation(targetClass, TrackFlow.class);
                if (classTrackFlow != null) {
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("flow", classTrackFlow.value().isEmpty() ? targetClass.getSimpleName() : classTrackFlow.value());
                    info.put("type", classTrackFlow.type());
                    info.put("source", "CLASS: " + targetClass.getName());
                    discoveredFlows.add(info);
                }

                // Check method-level @TrackFlow
                for (Method method : targetClass.getDeclaredMethods()) {
                    TrackFlow methodTrackFlow = AnnotationUtils.findAnnotation(method, TrackFlow.class);
                    if (methodTrackFlow != null) {
                        Map<String, String> info = new LinkedHashMap<>();
                        info.put("flow", methodTrackFlow.value().isEmpty() ? method.getName() : methodTrackFlow.value());
                        info.put("type", methodTrackFlow.type());
                        info.put("source", "METHOD: " + targetClass.getSimpleName() + "#" + method.getName());
                        discoveredFlows.add(info);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        result.put("count", discoveredFlows.size());
        result.put("flows", discoveredFlows);
        return result;
    }
}
