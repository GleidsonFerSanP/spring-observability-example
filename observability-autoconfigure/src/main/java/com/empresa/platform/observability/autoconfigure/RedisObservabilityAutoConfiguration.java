package com.empresa.platform.observability.autoconfigure;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.data.redis.connection.RedisConnectionFactory")
public class RedisObservabilityAutoConfiguration {
}
