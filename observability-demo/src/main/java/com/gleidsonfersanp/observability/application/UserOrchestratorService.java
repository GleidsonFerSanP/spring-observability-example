package com.gleidsonfersanp.observability.application;

import com.gleidsonfersanp.observability.cache.UserCustomerCache;
import com.gleidsonfersanp.observability.domain.BillingDto;
import com.gleidsonfersanp.observability.domain.BillingType;
import com.gleidsonfersanp.observability.domain.CustomerDto;
import com.gleidsonfersanp.observability.domain.NotificationResponse;
import com.gleidsonfersanp.observability.domain.UserProfile;
import com.gleidsonfersanp.observability.domain.UserRegistrationRequest;
import com.gleidsonfersanp.observability.feature.FeatureToggleService;
import com.gleidsonfersanp.observability.integration.BillingClient;
import com.gleidsonfersanp.observability.integration.CustomerClient;
import com.gleidsonfersanp.observability.integration.NotificationClient;
import com.gleidsonfersanp.observability.integration.messaging.KafkaUserProducer;
import com.gleidsonfersanp.observability.integration.messaging.SqsUserProducer;
import com.empresa.platform.observability.core.annotation.ComponentType;
import com.empresa.platform.observability.core.annotation.MDC;
import com.empresa.platform.observability.core.annotation.ObservationTag;
import com.empresa.platform.observability.core.annotation.TrackStep;
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
    private final SqsUserProducer sqsUserProducer;
    private final UserCustomerCache userCustomerCache;
    private final FeatureToggleService featureToggleService;

    public UserOrchestratorService(CustomerClient customerClient,
                                   BillingClient billingClient,
                                   NotificationClient notificationClient,
                                   KafkaUserProducer kafkaProducer,
                                   SqsUserProducer sqsUserProducer,
                                   UserCustomerCache userCustomerCache,
                                   FeatureToggleService featureToggleService) {
        this.customerClient = customerClient;
        this.billingClient = billingClient;
        this.notificationClient = notificationClient;
        this.kafkaProducer = kafkaProducer;
        this.sqsUserProducer = sqsUserProducer;
        this.userCustomerCache = userCustomerCache;
        this.featureToggleService = featureToggleService;
    }

    @Observed(name = "user.registration.initiate", contextualName = "initiate-async-registration")
    @TrackStep(name = "initiate-async-registration", type = ComponentType.BUSINESS)
    @MDC(key = "userId", expression = "#request.userId")
    public void initiateUserRegistration(UserRegistrationRequest request) {
        log.info("Initiating async registration for user: {}", request.userId());
        kafkaProducer.publishUserRegistration(request);
    }

    @CircuitBreaker(name = "orchestrator", fallbackMethod = "orchestratorFallback")
    @Observed(name = "user.profile.provision", contextualName = "provision-user-profile")
    @ObservationTag(key = "userId", expression = "#userId", highCardinality = true)
    @ObservationTag(key = "flow", expression = "'provisioning'")
    @ObservationTag(key = "customer_plan", expression = "#result?.billing()?.plan()")
    @MDC(key = "flowType", value = "orchestrated-provisioning")
    public UserProfile fetchAndProvisionUserProfile(String userId) {
        log.info("Executing provisionUserProfile for user: {}", userId);
        if (featureToggleService.isEnabled("user-provisioning-v2")) {
            return provisionUserProfileV2(userId);
        }
        return provisionUserProfileLegacy(userId);
    }

    private UserProfile provisionUserProfileLegacy(String userId) {
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

    private UserProfile provisionUserProfileV2(String userId) {
        // Rota V2 de processamento: Redis Cache -> Billing API -> SQS Queue
        CustomerDto customer = userCustomerCache.getCustomer(userId);
        BillingDto billing = billingClient.getBillingInfo(userId);
        sqsUserProducer.publishWelcomeEmail(userId);

        return new UserProfile(
                userId,
                customer,
                billing,
                "DELIVERED_ASYNC_SQS"
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
