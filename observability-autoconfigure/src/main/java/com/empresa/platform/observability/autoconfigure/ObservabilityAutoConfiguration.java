package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.autoconfigure.actuator.ObservabilityEndpoint;
import com.empresa.platform.observability.autoconfigure.actuator.ObservabilityFlowsEndpoint;
import com.empresa.platform.observability.autoconfigure.actuator.ObservabilityMetricsEndpoint;
import com.empresa.platform.observability.autoconfigure.alerting.LogAlertNotifier;
import com.empresa.platform.observability.autoconfigure.alerting.WebhookAlertNotifier;
import com.empresa.platform.observability.autoconfigure.aspect.FlowTrackingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.LegLoggingAspect;
import com.empresa.platform.observability.autoconfigure.aspect.SpelObservationAspect;
import com.empresa.platform.observability.autoconfigure.detector.TracingRuntimeDetector;
import com.empresa.platform.observability.autoconfigure.report.ObservabilityStartupReporter;
import com.empresa.platform.observability.autoconfigure.validator.ObservabilityTopologyValidator;
import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertNotifier;
import com.empresa.platform.observability.core.alerting.AlertingProperties;
import com.empresa.platform.observability.core.engine.DatadogObservabilityEngine;
import com.empresa.platform.observability.core.engine.MicrometerObservabilityEngine;
import com.empresa.platform.observability.core.engine.ObservabilityEngine;
import com.empresa.platform.observability.core.feature.FeatureEvaluationListener;
import com.empresa.platform.observability.core.feature.FlowFeatureEvaluationListener;
import com.empresa.platform.observability.core.flow.FlowVariantProvider;
import com.empresa.platform.observability.core.leg.SpelMaskingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@ConditionalOnProperty(prefix = "observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConfigurationProperties(prefix = "app.observability.alerting")
    public AlertingProperties alertingProperties(ObservabilityProperties properties) {
        AlertingProperties ap = properties.getAlerting();
        return ap != null ? ap : new AlertingProperties();
    }

    @Bean
    @ConditionalOnMissingBean
    public SpelMaskingService spelMaskingService(@Autowired(required = false) ObjectMapper objectMapper) {
        return new SpelMaskingService(objectMapper != null ? objectMapper : new ObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.alerting", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AlertDispatcher alertDispatcher(
            AlertingProperties alertingProperties,
            @Autowired(required = false) List<AlertNotifier> notifiers,
            @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        return new AlertDispatcher(alertingProperties, notifiers, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(name = "logAlertNotifier")
    @ConditionalOnProperty(prefix = "observability.alerting", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LogAlertNotifier logAlertNotifier() {
        return new LogAlertNotifier();
    }

    @Bean
    @ConditionalOnMissingBean(name = "webhookAlertNotifier")
    @ConditionalOnProperty(prefix = "observability.alerting", name = "webhook-url")
    public WebhookAlertNotifier webhookAlertNotifier(
            AlertingProperties alertingProperties,
            @Autowired(required = false) RestTemplateBuilder builder
    ) {
        return new WebhookAlertNotifier(alertingProperties, builder);
    }

    @Bean
    @ConditionalOnMissingBean(ObservabilityEngine.class)
    public ObservabilityEngine observabilityEngine(
            ObservabilityProperties properties,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        ObservationRegistry obsReg = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
        String profile = properties.getProfile();
        String engine = properties.getEngine();
        if ("prometheus".equalsIgnoreCase(profile) || "micrometer".equalsIgnoreCase(engine) || "prometheus".equalsIgnoreCase(engine)) {
            return new MicrometerObservabilityEngine(obsReg, meterRegistry);
        }
        return new DatadogObservabilityEngine(obsReg, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = {"observability.flow-tracking.enabled", "observability.flow.enabled"}, havingValue = "true", matchIfMissing = true)
    public FlowTrackingAspect flowTrackingAspect(
            ObservabilityEngine observabilityEngine,
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) AlertDispatcher alertDispatcher,
            AlertingProperties alertingProperties,
            @Autowired(required = false) FlowVariantProvider variantProvider
    ) {
        return new FlowTrackingAspect(
                observabilityEngine,
                meterRegistry,
                observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP,
                alertDispatcher,
                alertingProperties,
                variantProvider
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.spel-observation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SpelObservationAspect spelObservationAspect(@Autowired(required = false) ObservationRegistry observationRegistry) {
        return new SpelObservationAspect(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.leg-logging", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LegLoggingAspect legLoggingAspect(
            SpelMaskingService spelMaskingService,
            @Autowired(required = false) ObjectMapper objectMapper
    ) {
        return new LegLoggingAspect(spelMaskingService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.observation-handler", name = "enabled", havingValue = "true", matchIfMissing = false)
    public LoggingObservationHandler loggingObservationHandler() {
        return new LoggingObservationHandler();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.correlation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.async-decorator", name = "enabled", havingValue = "true", matchIfMissing = true)
    public com.empresa.platform.observability.autoconfigure.async.ObservabilityTaskDecorator observabilityTaskDecorator() {
        return new com.empresa.platform.observability.autoconfigure.async.ObservabilityTaskDecorator();
    }

    @Bean
    @ConditionalOnMissingBean(FeatureEvaluationListener.class)
    public FlowFeatureEvaluationListener flowFeatureEvaluationListener(
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) ObservabilityEngine observabilityEngine) {
        return new FlowFeatureEvaluationListener(observationRegistry, observabilityEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public TracingRuntimeDetector tracingRuntimeDetector() {
        return new TracingRuntimeDetector();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityTopologyValidator observabilityTopologyValidator(
            ObservabilityProperties properties,
            TracingRuntimeDetector detector,
            @Autowired(required = false) List<MeterRegistry> registries) {
        ObservabilityTopologyValidator validator = new ObservabilityTopologyValidator(properties, detector);
        if (registries != null) {
            validator.validate(registries);
        }
        return validator;
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityStartupReporter observabilityStartupReporter(
            ObservabilityProperties properties,
            TracingRuntimeDetector detector,
            ObservabilityTopologyValidator validator,
            @Autowired(required = false) List<MeterRegistry> registries) {
        ObservabilityStartupReporter reporter = new ObservabilityStartupReporter(properties, detector, validator);
        if (registries != null) {
            reporter.logReport(registries);
        }
        return reporter;
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnMissingBean
    public ObservabilityEndpoint observabilityEndpoint(
            ObservabilityProperties properties,
            TracingRuntimeDetector detector,
            ObservabilityTopologyValidator validator,
            @Autowired(required = false) List<MeterRegistry> registries) {
        return new ObservabilityEndpoint(properties, detector, validator, registries != null ? registries : List.of());
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnMissingBean
    public ObservabilityMetricsEndpoint observabilityMetricsEndpoint(
            @Autowired(required = false) List<MeterRegistry> registries) {
        return new ObservabilityMetricsEndpoint(registries != null ? registries : List.of());
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnMissingBean
    public ObservabilityFlowsEndpoint observabilityFlowsEndpoint(ApplicationContext applicationContext) {
        return new ObservabilityFlowsEndpoint(applicationContext);
    }
}
