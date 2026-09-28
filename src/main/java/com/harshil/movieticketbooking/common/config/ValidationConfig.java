package com.harshil.movieticketbooking.common.config;

import jakarta.validation.Configuration;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Makes bean validation read time from the same {@link Clock} as everything
 * else.
 * <p>
 * {@code @Future} and {@code @Past} are evaluated against a
 * {@code ClockProvider}, which by default is the system clock - so they would
 * ignore the application's injected clock entirely. That is a real
 * inconsistency, not just a testing inconvenience: a request could be rejected
 * as "in the past" by the validator while the service layer, reading the
 * injected clock, considers it comfortably in the future. The two would
 * disagree about what time it is.
 * <p>
 * Declaring this bean also backs off Boot's {@code defaultValidator}
 * auto-configuration, which is conditional on no {@code Validator} being
 * defined. {@code @ConditionalOnMissingBean} is reliable there, unlike on a
 * component-scanned bean, because auto-configuration is evaluated after
 * user-defined beans are registered.
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class ValidationConfig {

    @Bean
    public LocalValidatorFactoryBean defaultValidator(Clock clock) {
        return new LocalValidatorFactoryBean() {

            @Override
            protected void postProcessConfiguration(Configuration<?> configuration) {
                configuration.clockProvider(() -> clock);
            }
        };
    }
}
