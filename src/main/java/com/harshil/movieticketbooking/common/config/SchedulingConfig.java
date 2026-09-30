package com.harshil.movieticketbooking.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the {@code @Scheduled} sweeps: expired-hold cleanup and show
 * reminders.
 * <p>
 * Both sweeps are safety nets, never the mechanism a guarantee depends on. An
 * expired hold becomes bookable because the hold transaction re-evaluates
 * expiry under the row lock, not because the sweeper ran; the sweeper only
 * tidies rows up so seat maps read correctly. Reminders are made
 * exactly-once by a unique dedupe key, not by the scheduler firing once.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
