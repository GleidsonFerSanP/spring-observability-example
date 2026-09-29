package com.gleidsonfersanp.observability.integration;

import com.gleidsonfersanp.observability.domain.NotificationResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "notification-service", url = "${app.integrations.notification.url}")
public interface NotificationClient {

    @CircuitBreaker(name = "notification-service")
    @PostMapping("/notifications")
    NotificationResponse sendNotification(@RequestBody Map<String, Object> payload);
}
