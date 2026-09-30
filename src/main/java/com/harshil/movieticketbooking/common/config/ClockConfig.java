package com.harshil.movieticketbooking.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one {@link Clock} the application reads time from.
 * <p>
 * No production code calls {@code Instant.now()} directly. Hold expiry,
 * weekend pricing, discount validity windows and refund bands are all decided
 * against this bean, so a test can replace it with a controllable clock and
 * assert "the hold expired" without sleeping for five minutes.
 * <p>
 * It is always UTC: every instant persisted or compared is absolute. Local
 * calendar questions - "is this show on a weekend?" - are answered by
 * projecting that instant into the city's own time zone at the point of use.
 * <p>
 * Tests override this by declaring their own {@code Clock} as
 * {@code @Primary}, which resolves deterministically. Guarding this bean with
 * {@code @ConditionalOnMissingBean} would not: outside auto-configuration that
 * condition depends on bean registration order.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
