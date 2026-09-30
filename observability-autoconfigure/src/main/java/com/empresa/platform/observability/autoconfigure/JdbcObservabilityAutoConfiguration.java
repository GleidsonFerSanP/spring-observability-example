package com.empresa.platform.observability.autoconfigure;

import com.empresa.platform.observability.autoconfigure.jdbc.HikariPoolAlertWatcher;
import com.empresa.platform.observability.core.alerting.AlertDispatcher;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnClass({DataSource.class, HikariDataSource.class})
@ConditionalOnProperty(prefix = "observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JdbcObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "observability.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
    public HikariPoolAlertWatcher hikariPoolAlertWatcher(
            @Autowired(required = false) MeterRegistry meterRegistry,
            @Autowired(required = false) AlertDispatcher alertDispatcher,
            ObservabilityProperties properties
    ) {
        return new HikariPoolAlertWatcher(meterRegistry, alertDispatcher, properties.getAlerting());
    }
}
