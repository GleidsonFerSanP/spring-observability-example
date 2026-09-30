package com.empresa.platform.observability.autoconfigure.resilience;

import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.empresa.platform.observability.core.alerting.AlertEvent;
import com.empresa.platform.observability.core.alerting.AlertSeverity;
import com.empresa.platform.observability.core.alerting.AlertType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class CircuitBreakerAlertListener implements RegistryEventConsumer<CircuitBreaker> {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerAlertListener.class);
    private final AlertDispatcher alertDispatcher;

    public CircuitBreakerAlertListener(AlertDispatcher alertDispatcher) {
        this.alertDispatcher = alertDispatcher;
    }

    @Override
    public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> entryAddedEvent) {
        CircuitBreaker cb = entryAddedEvent.getAddedEntry();
        log.info("Registrando monitoramento de alarmística para o Circuit Breaker: '{}'", cb.getName());

        cb.getEventPublisher().onStateTransition(event -> {
            if (alertDispatcher == null) return;
            CircuitBreaker.StateTransition transition = event.getStateTransition();
            String cbName = cb.getName();

            if (transition.getToState() == CircuitBreaker.State.OPEN) {
                alertDispatcher.dispatch(AlertEvent.of(
                        AlertType.CIRCUIT_BREAKER_OPEN,
                        AlertSeverity.CRITICAL,
                        "Resilience4j",
                        cbName,
                        String.format("Disjuntor '%s' abriu (OPEN) após taxa de falhas exceder o limiar de tolerância. Tráfego bloqueado.", cbName),
                        1.0,
                        0.0,
                        Map.of(
                                "fromState", transition.getFromState().name(),
                                "toState", transition.getToState().name(),
                                "failureRate", cb.getMetrics().getFailureRate(),
                                "numberOfFailedCalls", cb.getMetrics().getNumberOfFailedCalls()
                        )
                ));
            } else if (transition.getToState() == CircuitBreaker.State.HALF_OPEN) {
                alertDispatcher.dispatch(AlertEvent.of(
                        AlertType.CIRCUIT_BREAKER_DEGRADED,
                        AlertSeverity.WARNING,
                        "Resilience4j",
                        cbName,
                        String.format("Disjuntor '%s' entrou em meio-aberto (HALF_OPEN). Realizando chamadas de teste para recuperação.", cbName),
                        0.5,
                        0.0,
                        Map.of(
                                "fromState", transition.getFromState().name(),
                                "toState", transition.getToState().name()
                        )
                ));
            } else if (transition.getToState() == CircuitBreaker.State.CLOSED && transition.getFromState() != CircuitBreaker.State.CLOSED) {
                alertDispatcher.dispatch(AlertEvent.of(
                        AlertType.CIRCUIT_BREAKER_DEGRADED,
                        AlertSeverity.INFO,
                        "Resilience4j",
                        cbName,
                        String.format("Disjuntor '%s' recuperado com sucesso. Estado voltou para FECHADO (CLOSED).", cbName),
                        0.0,
                        0.0,
                        Map.of(
                                "fromState", transition.getFromState().name(),
                                "toState", transition.getToState().name()
                        )
                ));
            }
        });
    }

    @Override
    public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> entryRemoveEvent) {
    }

    @Override
    public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> entryReplacedEvent) {
    }
}
