package com.gleidsonfersanp.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.empresa.platform.observability.core.leg.*;
import com.empresa.platform.observability.core.annotation.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpelMaskingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpelMaskingService maskingService = new SpelMaskingService(objectMapper);

    public static class SampleUserDto {
        private String userId;
        private String email;
        private String cpf;
        private String password;
        private String cardNumber;
        private String plan;

        public SampleUserDto(String userId, String email, String cpf, String password, String cardNumber, String plan) {
            this.userId = userId;
            this.email = email;
            this.cpf = cpf;
            this.password = password;
            this.cardNumber = cardNumber;
            this.plan = plan;
        }

        public String getUserId() { return userId; }
        public String getEmail() { return email; }
        public String getCpf() { return cpf; }
        public String getPassword() { return password; }
        public String getCardNumber() { return cardNumber; }
        public String getPlan() { return plan; }
    }

    private MaskField createMaskField(String expression, MaskPattern pattern) {
        return new MaskField() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return MaskField.class;
            }

            @Override
            public String expression() {
                return expression;
            }

            @Override
            public MaskPattern pattern() {
                return pattern;
            }

            @Override
            public String customMask() {
                return "";
            }
        };
    }

    @Test
    @DisplayName("Deve mascarar email parcialmente preservando domínio e primeira letra")
    void testEmailMasking() throws NoSuchMethodException {
        SampleUserDto user = new SampleUserDto("u1", "john.doe@example.com", "12345678900", "secret123", "4111222233334444", "PREMIUM");
        Method method = this.getClass().getDeclaredMethod("testEmailMasking");

        MaskField[] masks = new MaskField[]{
            createMaskField("#result?.email", MaskPattern.EMAIL_PARTIAL)
        };

        JsonNode masked = maskingService.maskPayload(user, method, new Object[]{}, user, masks);

        assertThat(masked.get("email").asText()).isEqualTo("j***e@example.com");
        // Verifica imutabilidade do DTO de domínio original
        assertThat(user.getEmail()).isEqualTo("john.doe@example.com");
    }

    @Test
    @DisplayName("Deve mascarar CPF, Senha, Cartão e aplicar Full Mask em plano")
    void testMultipleMaskPatterns() throws NoSuchMethodException {
        SampleUserDto user = new SampleUserDto("u1", "user@test.com", "123.456.789-00", "myPassword", "4111111111111234", "ENTERPRISE");
        Method method = this.getClass().getDeclaredMethod("testMultipleMaskPatterns");

        MaskField[] masks = new MaskField[]{
            createMaskField("#result?.cpf", MaskPattern.CPF_PARTIAL),
            createMaskField("#result?.password", MaskPattern.PASSWORD),
            createMaskField("#result?.cardNumber", MaskPattern.CARD_PARTIAL),
            createMaskField("#result?.plan", MaskPattern.FULL_MASK)
        };

        JsonNode masked = maskingService.maskPayload(user, method, new Object[]{}, user, masks);

        assertThat(masked.get("cpf").asText()).isEqualTo("123.***.***-00");
        assertThat(masked.get("password").asText()).isEqualTo("********");
        assertThat(masked.get("cardNumber").asText()).isEqualTo("************1234");
        assertThat(masked.get("plan").asText()).isEqualTo("***REDACTED***");
        assertThat(masked.get("userId").asText()).isEqualTo("u1");

        // DTO original continua estritamente intacto
        assertThat(user.getPassword()).isEqualTo("myPassword");
        assertThat(user.getPlan()).isEqualTo("ENTERPRISE");
    }

    @Test
    @DisplayName("Deve gerenciar contexto sequencial de pernas via LegContext")
    void testLegContextLifecycle() {
        LegContext.clear();
        assertThat(LegContext.currentLeg()).isNull();

        LegContext.LegSnapshot rootLeg = LegContext.startLeg("UserOrchestratorApi", LegType.INBOUND);
        assertThat(rootLeg.legNumber()).isEqualTo(1);
        assertThat(rootLeg.parentLegNumber()).isNull();
        assertThat(rootLeg.target()).isEqualTo("UserOrchestratorApi");
        assertThat(rootLeg.type()).isEqualTo(LegType.INBOUND);

        LegContext.LegSnapshot childLeg = LegContext.startLeg("customer-service", LegType.OUTBOUND);
        assertThat(childLeg.legNumber()).isEqualTo(2);
        assertThat(childLeg.parentLegNumber()).isEqualTo(1);
        assertThat(childLeg.target()).isEqualTo("customer-service");

        LegContext.LegSnapshot poppedChild = LegContext.completeLeg();
        assertThat(poppedChild).isEqualTo(childLeg);

        LegContext.LegSnapshot current = LegContext.currentLeg();
        assertThat(current).isEqualTo(rootLeg);

        LegContext.completeLeg();
        assertThat(LegContext.currentLeg()).isNull();
        LegContext.clear();
    }
}
