package com.empresa.platform.observability.core.leg;

import com.empresa.platform.observability.core.annotation.LogLeg;
import com.empresa.platform.observability.core.annotation.MaskField;
import com.empresa.platform.observability.core.annotation.MaskPattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SpelMaskingServiceCoreTest {

    private SpelMaskingService maskingService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        maskingService = new SpelMaskingService(objectMapper);
    }

    @Test
    @DisplayName("Deve validar algoritmos do enum MaskPattern")
    void shouldValidateMaskPatternAlgorithms() {
        assertThat(MaskPattern.CPF_PARTIAL.apply("123.456.789-00")).isEqualTo("123.***.***-00");
        assertThat(MaskPattern.EMAIL_PARTIAL.apply("john.doe@example.com")).isEqualTo("j***e@example.com");
        assertThat(MaskPattern.FULL_MASK.apply("secret")).isEqualTo("***REDACTED***");
        assertThat(MaskPattern.PASSWORD.apply("mypassword")).isEqualTo("********");
        assertThat(MaskPattern.CARD_PARTIAL.apply("4111222233334444")).isEqualTo("************4444");
    }

    record UserPayload(String name, String email, String cpf, String password) {}

    static class SampleService {
        @LogLeg(
                target = "sample-service",
                includePayload = true,
                mask = {
                        @MaskField(expression = "#user.email", pattern = MaskPattern.EMAIL_PARTIAL),
                        @MaskField(expression = "#user.cpf", pattern = MaskPattern.CPF_PARTIAL),
                        @MaskField(expression = "#user.password", pattern = MaskPattern.PASSWORD)
                }
        )
        public void processUser(UserPayload user) {}
    }

    @Test
    @DisplayName("Deve aplicar múltiplos mascaramentos em payload JSON com SpEL a partir de @LogLeg")
    void shouldApplyMultipleMasksOnPayload() throws NoSuchMethodException {
        UserPayload payload = new UserPayload("Carlos Silva", "carlos@empresa.com", "123.456.789-00", "mysecret");
        Method method = SampleService.class.getMethod("processUser", UserPayload.class);
        LogLeg logLeg = method.getAnnotation(LogLeg.class);
        MaskField[] masks = logLeg.mask();

        JsonNode maskedJson = maskingService.maskPayload(payload, method, new Object[]{payload}, null, masks);

        assertThat(maskedJson).isNotNull();
        assertThat(maskedJson.get("name").asText()).isEqualTo("Carlos Silva");
        assertThat(maskedJson.get("email").asText()).isEqualTo("c***s@empresa.com");
        assertThat(maskedJson.get("cpf").asText()).isEqualTo("123.***.***-00");
        assertThat(maskedJson.get("password").asText()).isEqualTo("********");
    }

    @Test
    @DisplayName("Deve lidar com payload nulo de forma segura")
    void shouldHandleNullPayloadGracefully() {
        JsonNode result = maskingService.maskPayload(null, null, null, null, null);
        assertThat(result).isNull();
    }
}
