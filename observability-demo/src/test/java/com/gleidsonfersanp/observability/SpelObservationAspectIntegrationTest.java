package com.gleidsonfersanp.observability;

import com.gleidsonfersanp.observability.domain.CustomerDto;
import com.gleidsonfersanp.observability.domain.UserRegistrationRequest;
import com.empresa.platform.observability.core.annotation.ObservationTag;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(SpelObservationAspectIntegrationTest.TestConfig.class)
class SpelObservationAspectIntegrationTest {

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private SampleObservedService sampleObservedService;

    @Autowired
    private TestObservationRecordingHandler observationRecordingHandler;

    @BeforeEach
    void setUp() {
        observationRecordingHandler.clear();
    }

    @AfterEach
    void tearDown() {
        observationRecordingHandler.clear();
    }

    @Test
    @DisplayName("Cenário 1: Extrai tags de parâmetros e de resultado com baixa e alta cardinalidade")
    void testParameterAndResultSpelTagExtraction() {
        UserRegistrationRequest request = new UserRegistrationRequest("user-99", "Ana Doe", "ana@example.com");

        CustomerDto result = sampleObservedService.registerCustomer("user-99", request);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Ana Doe");

        Observation.Context stoppedContext = observationRecordingHandler.findContextByName("sample.customer.register");
        assertThat(stoppedContext).isNotNull();

        // 1. Tag de parâmetro simples (#userId) - Alta cardinalidade
        KeyValue userIdTag = findHighCardinalityTag(stoppedContext, "user_id");
        assertThat(userIdTag).isNotNull();
        assertThat(userIdTag.getValue()).isEqualTo("user-99");

        // 2. Tag de objeto de parâmetro (#request.email()) - Alta cardinalidade
        KeyValue emailTag = findHighCardinalityTag(stoppedContext, "user_email");
        assertThat(emailTag).isNotNull();
        assertThat(emailTag.getValue()).isEqualTo("ana@example.com");

        // 3. Tag constante ('registration') - Baixa cardinalidade
        KeyValue operationTag = findLowCardinalityTag(stoppedContext, "operation");
        assertThat(operationTag).isNotNull();
        assertThat(operationTag.getValue()).isEqualTo("registration");

        // 4. Tag de resultado (#result.name()) - Baixa cardinalidade
        KeyValue customerNameTag = findLowCardinalityTag(stoppedContext, "customer_name");
        assertThat(customerNameTag).isNotNull();
        assertThat(customerNameTag.getValue()).isEqualTo("Ana Doe");
    }

    @Test
    @DisplayName("Cenário 2: Resiliência contra SpEL nulo ou expressão inexistente sem quebrar a execução do método")
    void testNullSafeAndInvalidSpelEvaluation() {
        // Método retorna null e a anotação tenta acessar #result?.name()
        CustomerDto result = sampleObservedService.getNullCustomer("non-existent-user");

        assertThat(result).isNull();

        Observation.Context stoppedContext = observationRecordingHandler.findContextByName("sample.customer.null");
        assertThat(stoppedContext).isNotNull();

        // Tag de parâmetro foi avaliada
        KeyValue userIdTag = findHighCardinalityTag(stoppedContext, "user_id");
        assertThat(userIdTag).isNotNull();
        assertThat(userIdTag.getValue()).isEqualTo("non-existent-user");

        // Tag de resultado seguro (#result?.name()) não gerou chave nula nem quebrou a chamada
        KeyValue nullResultTag = findLowCardinalityTag(stoppedContext, "customer_name");
        assertThat(nullResultTag).isNull();
    }

    @Test
    @DisplayName("Cenário 3: Exceção no método de negócio preserva erro e não impede captura das tags de pré-execução")
    void testExceptionPreservesPreEvaluationTagsAndThrowsNaturally() {
        assertThatThrownBy(() -> sampleObservedService.failingMethod("user-err"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated business failure");

        Observation.Context stoppedContext = observationRecordingHandler.findContextByName("sample.customer.fail");
        assertThat(stoppedContext).isNotNull();

        // Pre-evaluation tag (#userId) foi adicionada com sucesso antes da exceção estourar
        KeyValue userIdTag = findHighCardinalityTag(stoppedContext, "user_id");
        assertThat(userIdTag).isNotNull();
        assertThat(userIdTag.getValue()).isEqualTo("user-err");

        // Verifica se a exceção foi registrada no contexto de observação
        assertThat(stoppedContext.getError()).isNotNull();
        assertThat(stoppedContext.getError()).isInstanceOf(IllegalStateException.class);
    }

    private KeyValue findLowCardinalityTag(Observation.Context context, String key) {
        return StreamSupport.stream(context.getLowCardinalityKeyValues().spliterator(), false)
                .filter(kv -> kv.getKey().equals(key))
                .findFirst()
                .orElse(null);
    }

    private KeyValue findHighCardinalityTag(Observation.Context context, String key) {
        return StreamSupport.stream(context.getHighCardinalityKeyValues().spliterator(), false)
                .filter(kv -> kv.getKey().equals(key))
                .findFirst()
                .orElse(null);
    }

    // ==========================================
    // Configuração e Beans de Teste
    // ==========================================

    @TestConfiguration
    static class TestConfig {

        @Bean
        public TestObservationRecordingHandler testObservationRecordingHandler() {
            return new TestObservationRecordingHandler();
        }

        @Bean
        public SampleObservedService sampleObservedService() {
            return new SampleObservedService();
        }
    }

    public static class TestObservationRecordingHandler implements ObservationHandler<Observation.Context> {
        private final List<Observation.Context> stoppedContexts = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stoppedContexts.add(context);
        }

        public void clear() {
            stoppedContexts.clear();
        }

        public Observation.Context findContextByName(String name) {
            return stoppedContexts.stream()
                    .filter(c -> name.equals(c.getName()))
                    .findFirst()
                    .orElse(null);
        }
    }

    @Service
    public static class SampleObservedService {

        @Observed(name = "sample.customer.register", contextualName = "sample-register")
        @ObservationTag(key = "user_id", expression = "#userId", highCardinality = true)
        @ObservationTag(key = "user_email", expression = "#request.email()", highCardinality = true)
        @ObservationTag(key = "operation", expression = "'registration'")
        @ObservationTag(key = "customer_name", expression = "#result.name()")
        public CustomerDto registerCustomer(String userId, UserRegistrationRequest request) {
            return new CustomerDto("Ana Doe", request.email());
        }

        @Observed(name = "sample.customer.null", contextualName = "sample-null")
        @ObservationTag(key = "user_id", expression = "#userId", highCardinality = true)
        @ObservationTag(key = "customer_name", expression = "#result?.name()")
        public CustomerDto getNullCustomer(String userId) {
            return null;
        }

        @Observed(name = "sample.customer.fail", contextualName = "sample-fail")
        @ObservationTag(key = "user_id", expression = "#userId", highCardinality = true)
        @ObservationTag(key = "customer_name", expression = "#result?.name()")
        public CustomerDto failingMethod(String userId) {
            throw new IllegalStateException("Simulated business failure");
        }
    }
}
