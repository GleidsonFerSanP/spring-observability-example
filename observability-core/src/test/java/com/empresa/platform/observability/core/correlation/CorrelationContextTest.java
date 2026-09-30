package com.empresa.platform.observability.core.correlation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationContextTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        CorrelationContext.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("Deve gerar CorrelationId automaticamente quando nenhum existir")
    void shouldGenerateCorrelationIdWhenNoneExists() {
        String cid = CorrelationContext.generateOrGet();
        assertThat(cid).isNotBlank();
        assertThat(CorrelationContext.getCorrelationId()).isEqualTo(cid);
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isEqualTo(cid);
    }

    @Test
    @DisplayName("Deve reutilizar CorrelationId previamente definido")
    void shouldReuseExistingCorrelationId() {
        String existing = "cid-" + UUID.randomUUID();
        CorrelationContext.setCorrelationId(existing);

        assertThat(CorrelationContext.getCorrelationId()).isEqualTo(existing);
        assertThat(CorrelationContext.generateOrGet()).isEqualTo(existing);
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isEqualTo(existing);
    }

    @Test
    @DisplayName("Deve limpar CorrelationId e MDC ao invocar clear()")
    void shouldClearCorrelationIdAndMdc() {
        CorrelationContext.setCorrelationId("cid-123");
        CorrelationContext.clear();

        assertThat(CorrelationContext.getCorrelationId()).isNull();
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("Deve executar Runnable com CorrelationId temporário e restaurar estado anterior")
    void shouldExecuteRunnableWithScopedCorrelationId() {
        CorrelationContext.setCorrelationId("initial-cid");

        CorrelationContext.runWithCorrelationId("temporary-cid", () -> {
            assertThat(CorrelationContext.getCorrelationId()).isEqualTo("temporary-cid");
            assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isEqualTo("temporary-cid");
        });

        assertThat(CorrelationContext.getCorrelationId()).isEqualTo("initial-cid");
        assertThat(MDC.get(CorrelationContext.CORRELATION_ID_KEY)).isEqualTo("initial-cid");
    }
}
