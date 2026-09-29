package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.domain.NotificationResponse;
import com.gleidsonfersanp.observability.observability.ObservationTag;
import com.gleidsonfersanp.observability.observability.flow.TrackStep;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import com.gleidsonfersanp.observability.observability.leg.LegType;
import com.gleidsonfersanp.observability.observability.leg.LogLeg;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "notification-service", url = "${app.integrations.notification.url}")
public interface NotificationClient {

    @ObservationTag(key = "client", expression = "'notification'")
    @TrackStep("API Notificação (POST /notifications)")
    @LogLeg(
        target = "notification-service",
        type = com.gleidsonfersanp.observability.observability.leg.LegType.OUTBOUND
    )
    @CircuitBreaker(name = "notification-service")
    @PostMapping("/notifications")
    NotificationResponse sendNotification(@RequestBody Map<String, Object> payload);
}
