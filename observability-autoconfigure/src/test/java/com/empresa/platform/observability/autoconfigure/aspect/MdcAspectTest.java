package com.empresa.platform.observability.autoconfigure.aspect;

import com.empresa.platform.observability.core.annotation.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MdcAspectTest {

    private MdcAspect aspect;

    static class SampleService {
        private final AtomicReference<String> capturedUserId = new AtomicReference<>();
        private final AtomicReference<String> capturedTenant = new AtomicReference<>();

        @MDC(key = "userId", expression = "#userId")
        @MDC(key = "orderId", expression = "#order.id")
        public String processOrder(String userId, OrderDto order) {
            capturedUserId.set(org.slf4j.MDC.get("userId"));
            return "SUCCESS";
        }

        @MDC(key = "tenant", expression = "#tenant")
        public void outerMethod(String tenant, SampleService self, String innerTenant) {
            capturedTenant.set(org.slf4j.MDC.get("tenant"));
            self.innerMethod(innerTenant);
            // Após inner retornar, outer ainda deve enxergar seu próprio tenant (semântica de pilha)
            capturedTenant.set(org.slf4j.MDC.get("tenant"));
        }

        @MDC(key = "tenant", expression = "#innerTenant")
        public void innerMethod(String innerTenant) {
            assertThat(org.slf4j.MDC.get("tenant")).isEqualTo(innerTenant);
        }

        @MDC(key = "userId", expression = "#userId")
        public void failMethod(String userId) {
            assertThat(org.slf4j.MDC.get("userId")).isEqualTo(userId);
            throw new IllegalStateException("Simulated failure");
        }

        public void processWithParamTag(@MDC(key = "customerId") String customerId) {
            capturedUserId.set(org.slf4j.MDC.get("customerId"));
        }
    }

    record OrderDto(String id, double amount) {}

    @BeforeEach
    void setUp() {
        org.slf4j.MDC.clear();
        aspect = new MdcAspect();
    }

    @AfterEach
    void tearDown() {
        org.slf4j.MDC.clear();
    }

    private SampleService createProxy(SampleService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Test
    @DisplayName("Deve injetar tags @MDC no MDC durante a execução do método e limpar após o término")
    void shouldInjectAndCleanMdcTags() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        String result = proxy.processOrder("user-123", new OrderDto("ord-999", 50.0));

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(service.capturedUserId.get()).isEqualTo("user-123");
        // Após o término do método, MDC deve estar limpo
        assertThat(org.slf4j.MDC.get("userId")).isNull();
        assertThat(org.slf4j.MDC.get("orderId")).isNull();
    }

    @Test
    @DisplayName("Deve preservar valor anterior do MDC em chamadas aninhadas (stack semantics)")
    void shouldHandleNestedMdcInvocations() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        proxy.outerMethod("tenant-A", proxy, "tenant-B");

        assertThat(service.capturedTenant.get()).isEqualTo("tenant-A");
        assertThat(org.slf4j.MDC.get("tenant")).isNull();
    }

    @Test
    @DisplayName("Deve limpar MDC garantidamente no bloco finally mesmo em caso de exceção")
    void shouldCleanMdcOnException() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        assertThatThrownBy(() -> proxy.failMethod("user-error"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated failure");

        // MDC deve estar limpo mesmo com exceção
        assertThat(org.slf4j.MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("Deve suportar @MDC diretamente em parâmetros do método")
    void shouldSupportParamLevelMdcTag() {
        SampleService service = new SampleService();
        SampleService proxy = createProxy(service);

        proxy.processWithParamTag("cust-777");

        assertThat(service.capturedUserId.get()).isEqualTo("cust-777");
        assertThat(org.slf4j.MDC.get("customerId")).isNull();
    }
}
