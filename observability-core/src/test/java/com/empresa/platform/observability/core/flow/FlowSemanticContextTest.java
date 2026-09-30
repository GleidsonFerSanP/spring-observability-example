package com.empresa.platform.observability.core.flow;

import com.empresa.platform.observability.core.annotation.BusinessOutcome;
import com.empresa.platform.observability.core.annotation.FlowStatus;
import com.empresa.platform.observability.core.cardinality.CardinalityPolicy;
import com.empresa.platform.observability.core.metric.MetricNamingPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FlowSemanticContextTest {

    @AfterEach
    void tearDown() {
        FlowSemanticContext.clear();
    }

    @Test
    @DisplayName("Deve inicializar e manipular FlowSemanticContext sem cálculo de pizza temporal")
    void shouldManageFlowSemanticContext() {
        FlowSemanticContext ctx = new FlowSemanticContext("payment-flow", "HTTP", "v2");
        FlowSemanticContext.set(ctx);

        assertThat(FlowSemanticContext.current()).isNotNull();
        assertThat(FlowSemanticContext.current().getFlowName()).isEqualTo("payment-flow");
        assertThat(FlowSemanticContext.current().getVariant()).isEqualTo("v2");
        assertThat(FlowSemanticContext.current().getStatus()).isEqualTo(FlowStatus.SUCCESS);
        assertThat(FlowSemanticContext.current().getBusinessOutcome()).isEqualTo(BusinessOutcome.COMPLETED);

        // Atualizar status e outcome
        ctx.setStatus(FlowStatus.FALLBACK);
        ctx.setBusinessOutcome(BusinessOutcome.FALLBACK_APPLIED);
        ctx.putDimension("region", "sa-east-1");

        assertThat(FlowSemanticContext.current().getStatus()).isEqualTo(FlowStatus.FALLBACK);
        assertThat(FlowSemanticContext.current().getBusinessOutcome()).isEqualTo(BusinessOutcome.FALLBACK_APPLIED);
        assertThat(FlowSemanticContext.current().getLowCardinalityDimensions()).containsEntry("region", "sa-east-1");

        FlowSemanticContext.clear();
        assertThat(FlowSemanticContext.current()).isNull();
    }

    @Test
    @DisplayName("Deve validar a política de cardinalidade (CardinalityPolicy)")
    void shouldEnforceCardinalityPolicy() {
        // High-cardinality keys must be rejected
        assertThat(CardinalityPolicy.isPermittedMetricTag("userId")).isFalse();
        assertThat(CardinalityPolicy.isPermittedMetricTag("customerId")).isFalse();
        assertThat(CardinalityPolicy.isPermittedMetricTag("orderId")).isFalse();
        assertThat(CardinalityPolicy.isPermittedMetricTag("traceId")).isFalse();
        assertThat(CardinalityPolicy.isPermittedMetricTag("correlationId")).isFalse();
        assertThat(CardinalityPolicy.isPermittedMetricTag("cpf")).isFalse();
        assertThat(CardinalityPolicy.isPermittedMetricTag("email")).isFalse();
        assertThat(CardinalityPolicy.isPermittedMetricTag("token")).isFalse();

        // Low-cardinality operational keys must be permitted
        assertThat(CardinalityPolicy.isPermittedMetricTag("flow")).isTrue();
        assertThat(CardinalityPolicy.isPermittedMetricTag("variant")).isTrue();
        assertThat(CardinalityPolicy.isPermittedMetricTag("status")).isTrue();
        assertThat(CardinalityPolicy.isPermittedMetricTag("region")).isTrue();
        assertThat(CardinalityPolicy.isPermittedMetricTag("environment")).isTrue();
    }

    @Test
    @DisplayName("Deve resolver variantes via FlowVariantProvider SPI")
    void shouldResolveVariantViaProvider() {
        FlowVariantProvider provider = () -> Optional.of(FlowVariant.of("canary-v2"));

        Optional<FlowVariant> variant = provider.resolve();
        assertThat(variant).isPresent();
        assertThat(variant.get().name()).isEqualTo("canary-v2");
    }

    @Test
    @DisplayName("Deve verificar convenções em MetricNamingPolicy")
    void shouldVerifyMetricNamingPolicy() {
        assertThat(MetricNamingPolicy.FLOW_EXECUTIONS).isEqualTo("corp.flow.executions");
        assertThat(MetricNamingPolicy.FLOW_DURATION).isEqualTo("corp.flow.duration");
        assertThat(MetricNamingPolicy.FLOW_ERRORS).isEqualTo("corp.flow.errors");
        assertThat(MetricNamingPolicy.FLOW_FALLBACKS).isEqualTo("corp.flow.fallbacks");
        assertThat(MetricNamingPolicy.TAG_FLOW).isEqualTo("flow");
        assertThat(MetricNamingPolicy.TAG_VARIANT).isEqualTo("variant");
    }
}
