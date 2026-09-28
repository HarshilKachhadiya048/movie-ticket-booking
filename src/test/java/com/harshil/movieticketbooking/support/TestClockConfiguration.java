package com.harshil.movieticketbooking.support;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application {@link Clock} with one tests control.
 * <p>
 * Marked {@code @Primary} so every injection point resolves to it regardless
 * of bean registration order.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    /**
     * A fixed Monday: 2026-03-09T09:00:00Z.
     * <p>
     * Monday rather than an arbitrary day, and deliberately early in the week,
     * so that the fixture's usual offsets - 6h, 18h, 48h ahead - all still
     * land on a weekday once converted into the fixture city's Asia/Kolkata
     * zone. Weekend pricing then has to be asked for explicitly by a test that
     * wants it, instead of appearing by accident depending on where an offset
     * happens to fall.
     */
    public static final Instant FIXED_NOW = Instant.parse("2026-03-09T09:00:00Z");

    @Bean
    @Primary
    public MutableClock testClock() {
        return MutableClock.at(FIXED_NOW);
    }
}
