package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.annotation.ObservationTag;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class SpelObservationAspectTest {

    private ObservationRegistry observationRegistry;
    private SpelObservationAspect aspect;

    @ObservationTag(key = "service", value = "sample-service")
    static class SampleService {

        @ObservationTag(key = "provider", expression = "#provider", lowCardinality = true)
        public String execute(String provider) {
            return "SUCCESS";
        }

        @ObservationTag(key = "userId", expression = "#userId", highCardinality = true)
        public String executeWithHighCardinality(String userId) {
            return "SUCCESS";
        }

        @ObservationTag(key = "messaging.system", value = "kafka")
        @ObservationTag(key = "client", value = "billing")
        public String executeWithFixedValues() {
            return "DONE";
        }

        @ObservationTag(key = "provider", value = "fixed-provider", expression = "#provider")
        public String executeWithPrecedence(String provider) {
            return "OVERRIDDEN";
        }

        @ObservationTag(key = "status", expression = "#result")
        public String executeWithResult() {
            return "APPROVED";
        }

        public String executeWithParamTag(@ObservationTag(key = "region") String region) {
            return "REGION_PROCESSED";
        }
    }

    @BeforeEach
    void setUp() {
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        aspect = new SpelObservationAspect(observationRegistry);
    }

    private SampleService createProxy(SampleService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private KeyValue findLowTag(Observation observation, String key) {
        return StreamSupport.stream(observation.getContext().getLowCardinalityKeyValues().spliterator(), false)
                .filter(kv -> kv.getKey().equals(key))
                .findFirst()
                .orElse(null);
    }

    private KeyValue findHighTag(Observation observation, String key) {
        return StreamSupport.stream(observation.getContext().getHighCardinalityKeyValues().spliterator(), false)
                .filter(kv -> kv.getKey().equals(key))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Deve enriquecer Observation com tags dinâmicas avaliadas via SpEL")
    void shouldEnrichObservationWithDynamicSpelTags() {
        SampleService proxy = createProxy(new SampleService());

        Observation observation = Observation.start("test-op", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
            proxy.execute("cielo");
        } finally {
            observation.stop();
        }

        KeyValue providerTag = findLowTag(observation, "provider");
        assertThat(providerTag).isNotNull();
        assertThat(providerTag.getValue()).isEqualTo("cielo");

        // Class-level tag
        KeyValue serviceTag = findLowTag(observation, "service");
        assertThat(serviceTag).isNotNull();
        assertThat(serviceTag.getValue()).isEqualTo("sample-service");
    }

    @Test
    @DisplayName("Deve enriquecer Observation com tags de valores fixos/estáticos sem SpEL")
    void shouldEnrichObservationWithFixedValues() {
        SampleService proxy = createProxy(new SampleService());

        Observation observation = Observation.start("test-fixed", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
            proxy.executeWithFixedValues();
        } finally {
            observation.stop();
        }

        KeyValue messagingTag = findLowTag(observation, "messaging.system");
        assertThat(messagingTag).isNotNull();
        assertThat(messagingTag.getValue()).isEqualTo("kafka");

        KeyValue clientTag = findLowTag(observation, "client");
        assertThat(clientTag).isNotNull();
        assertThat(clientTag.getValue()).isEqualTo("billing");
    }

    @Test
    @DisplayName("Deve dar precedência ao valor fixo (value) sobre a expressão (expression)")
    void shouldPrioritizeFixedValueOverExpression() {
        SampleService proxy = createProxy(new SampleService());

        Observation observation = Observation.start("test-precedence", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
            proxy.executeWithPrecedence("dynamic-provider");
        } finally {
            observation.stop();
        }

        KeyValue providerTag = findLowTag(observation, "provider");
        assertThat(providerTag).isNotNull();
        assertThat(providerTag.getValue()).isEqualTo("fixed-provider");
    }

    @Test
    @DisplayName("Deve avaliar tags de resultado pós-execução")
    void shouldEvaluatePostExecutionResultTag() {
        SampleService proxy = createProxy(new SampleService());

        Observation observation = Observation.start("test-result", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
            proxy.executeWithResult();
        } finally {
            observation.stop();
        }

        KeyValue statusTag = findLowTag(observation, "status");
        assertThat(statusTag).isNotNull();
        assertThat(statusTag.getValue()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Deve capturar tags em nível de parâmetro")
    void shouldCaptureParameterLevelTag() {
        SampleService proxy = createProxy(new SampleService());

        Observation observation = Observation.start("test-param", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
            proxy.executeWithParamTag("sa-east-1");
        } finally {
            observation.stop();
        }

        KeyValue regionTag = findLowTag(observation, "region");
        assertThat(regionTag).isNotNull();
        assertThat(regionTag.getValue()).isEqualTo("sa-east-1");
    }

    @Test
    @DisplayName("Deve registrar tag com alta cardinalidade para chaves sensíveis/tracing")
    void shouldRegisterHighCardinalityTag() {
        SampleService proxy = createProxy(new SampleService());

        Observation observation = Observation.start("test-high-card", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
            proxy.executeWithHighCardinality("user-12345");
        } finally {
            observation.stop();
        }

        KeyValue userIdTag = findHighTag(observation, "userId");
        assertThat(userIdTag).isNotNull();
        assertThat(userIdTag.getValue()).isEqualTo("user-12345");
    }
}
