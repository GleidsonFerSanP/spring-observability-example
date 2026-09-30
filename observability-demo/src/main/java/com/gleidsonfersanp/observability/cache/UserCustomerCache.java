package com.gleidsonfersanp.observability.cache;

import com.empresa.platform.observability.core.annotation.ComponentType;
import com.empresa.platform.observability.core.annotation.TrackStep;
import com.gleidsonfersanp.observability.domain.CustomerDto;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Componente simulando camada de Cache distribuído Redis na rota v2.
 * Instrumentado com @TrackStep para fornecer fatia e atribuição explícita de latência do tipo CACHE.
 */
@Component
public class UserCustomerCache {

    private final Map<String, CustomerDto> localCache = new ConcurrentHashMap<>();

    public UserCustomerCache() {
        localCache.put("user1", new CustomerDto("John Doe (Cached)", "johndoe@example.com"));
        localCache.put("user2", new CustomerDto("Jane Doe (Cached)", "janedoe@example.com"));
    }

    @TrackStep(value = "Cache Redis (GET customer:{userId})", type = ComponentType.CACHE)
    public CustomerDto getCustomer(String userId) {
        return localCache.computeIfAbsent(userId, id -> new CustomerDto("Fast Cached " + id, id + "@fast-cache.com"));
    }

    public void put(String userId, CustomerDto dto) {
        localCache.put(userId, dto);
    }
}
