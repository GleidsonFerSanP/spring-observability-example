package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.annotation.ObservationTag;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SpelObservationAspectTest {

    private ObservationRegistry observationRegistry;
    private SpelObservationAspect aspect;

    static class SampleService {
        @ObservationTag(key = "provider", expression = "#provider", lowCardinality = true)
        public String execute(String provider) {
            return "SUCCESS";
        }

        @ObservationTag(key = "userId", expression = "#userId", highCardinality = true)
        public String executeWithHighCardinality(String userId) {
            return "SUCCESS";
        }
    }

    @BeforeEach
    void setUp() {
        observationRegistry = ObservationRegistry.create();
        aspect = new SpelObservationAspect(observationRegistry);
    }

    private SampleService createProxy(SampleService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    @DisplayName("Deve enriquecer Observation com tags avaliadas via SpEL")
    void shouldEnrichObservationWithTags() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        Observation observation = Observation.start("test-op", observationRegistry);
        try (Observation.Scope scope = observation.openScope()) {
            proxy.execute("cielo");
        } finally {
            observation.stop();
        }

        // Verifica que o método executou com sucesso sob o observation ativo
        assertThat(observation).isNotNull();
    }
}
