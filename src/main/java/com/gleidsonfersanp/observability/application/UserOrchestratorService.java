package com.gleidsonfersanp.observability.application;

import com.gleidsonfersanp.observability.domain.BillingDto;
import com.gleidsonfersanp.observability.domain.BillingType;
import com.gleidsonfersanp.observability.domain.CustomerDto;
import com.gleidsonfersanp.observability.domain.NotificationResponse;
import com.gleidsonfersanp.observability.domain.UserProfile;
import com.gleidsonfersanp.observability.domain.UserRegistrationRequest;
import com.gleidsonfersanp.observability.integration.BillingClient;
import com.gleidsonfersanp.observability.integration.CustomerClient;
import com.gleidsonfersanp.observability.integration.NotificationClient;
import com.gleidsonfersanp.observability.integration.messaging.KafkaUserProducer;
import com.gleidsonfersanp.observability.observability.ObservationTag;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UserOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(UserOrchestratorService.class);

    private final CustomerClient customerClient;
    private final BillingClient billingClient;
    private final NotificationClient notificationClient;
    private final KafkaUserProducer kafkaProducer;

    public UserOrchestratorService(CustomerClient customerClient, BillingClient billingClient, 
                                   NotificationClient notificationClient, KafkaUserProducer kafkaProducer) {
        this.customerClient = customerClient;
        this.billingClient = billingClient;
        this.notificationClient = notificationClient;
        this.kafkaProducer = kafkaProducer;
    }

    @Observed(name = "user.registration.initiate", contextualName = "initiate-async-registration")
    @ObservationTag(key = "userId", expression = "#request.userId()", highCardinality = true)
    public void initiateUserRegistration(UserRegistrationRequest request) {
        kafkaProducer.publishUserRegistration(request);
    }

    @CircuitBreaker(name = "orchestrator", fallbackMethod = "orchestratorFallback")
    @Observed(name = "user.profile.provision", contextualName = "provision-user-profile")
    @ObservationTag(key = "userId", expression = "#userId", highCardinality = true)
    @ObservationTag(key = "flow", expression = "'provisioning'")
    @ObservationTag(key = "customer_plan", expression = "#result?.billing()?.plan()")
    public UserProfile fetchAndProvisionUserProfile(String userId) {
        
        CustomerDto customer = customerClient.getCustomerInfo(userId);
        BillingDto billing = billingClient.getBillingInfo(userId);

        NotificationResponse notificationResponse = notificationClient.sendNotification(
                Map.of(
                        "userId", userId,
                        "message", "Profile accessed and provisioned for " + customer.name()
                )
        );

        return new UserProfile(
                userId,
                customer,
                billing,
                notificationResponse.status()
        );
    }

    public UserProfile orchestratorFallback(String userId, Throwable t) {
        log.error("Circuit breaker fallback triggered for userId: {} due to: {}", userId, t.getMessage());
        return new UserProfile(
                userId,
                new CustomerDto("Unknown (Fallback)", "unknown@fallback.com"),
                new BillingDto("Unknown Plan", "Unavailable", BillingType.UNDEFINED),
                "FAILED_DUE_TO_FALLBACK"
        );
    }
}
