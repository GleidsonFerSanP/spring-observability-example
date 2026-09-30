package com.gleidsonfersanp.observability.integration;

import com.empresa.platform.observability.core.annotation.LegType;
import com.empresa.platform.observability.core.annotation.LogLeg;
import com.empresa.platform.observability.core.annotation.ObservationTag;
import com.empresa.platform.observability.core.annotation.TrackStep;
import com.gleidsonfersanp.observability.domain.NotificationResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "notification-service", url = "${app.integrations.notification.url}")
public interface NotificationClient {

    @ObservationTag(key = "client", value = "notification")
    @TrackStep("API Notificação (POST /notifications)")
    @LogLeg(
        target = "notification-service",
        type = LegType.OUTBOUND
    )
    @CircuitBreaker(name = "notification-service")
    @PostMapping("/notifications")
    NotificationResponse sendNotification(@RequestBody Map<String, Object> payload);
}
