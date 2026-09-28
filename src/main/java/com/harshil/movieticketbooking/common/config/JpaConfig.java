package com.harshil.movieticketbooking.common.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables {@code @CreatedDate} / {@code @LastModifiedDate} on
 * {@link com.harshil.movieticketbooking.common.domain.BaseEntity}, sourcing the
 * timestamps from the application {@link Clock} rather than from the system
 * clock, so audit columns move with the clock a test installs.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = JpaConfig.AUDITING_DATE_TIME_PROVIDER)
public class JpaConfig {

    public static final String AUDITING_DATE_TIME_PROVIDER = "auditingDateTimeProvider";

    @Bean(name = AUDITING_DATE_TIME_PROVIDER)
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
