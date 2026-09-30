package com.harshil.movieticketbooking.refund.service;

import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import com.harshil.movieticketbooking.refund.domain.RefundPolicyRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns "how long until the show" plus a policy into a refund amount.
 * <p>
 * Pure arithmetic with no repository, clock or entity mutation, which is what
 * makes the refund ladder exhaustively unit-testable without a database.
 * <p>
 * <b>Fails closed.</b> A policy with no band covering the elapsed time yields
 * 0%, not 100%. Misconfiguration should under-refund and be noticed, not
 * quietly give money away.
 */
@Component
public class RefundCalculator {

    private static final int HOURS_SCALE = 2;
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);

    /**
     * Hours remaining until the show, to two decimal places.
     * <p>
     * Computed from seconds rather than whole hours so a cancellation at
     * 23 hours 59 minutes is not rounded up into the 24-hour band and
     * over-refunded. Negative once the show has started.
     */
    public BigDecimal hoursUntil(Instant showStartsAt, Instant now) {
        long seconds = Duration.between(now, showStartsAt).toSeconds();
        return BigDecimal.valueOf(seconds)
                .divide(SECONDS_PER_HOUR, HOURS_SCALE, RoundingMode.DOWN);
    }

    /**
     * Applies {@code policy} to an amount.
     *
     * @param policy          the resolved policy, or null when none is
     *                        configured - which yields no refund
     * @param originalAmount  what the customer paid
     * @param hoursBeforeShow from {@link #hoursUntil}
     */
    public RefundAssessment assess(RefundPolicy policy, BigDecimal originalAmount, BigDecimal hoursBeforeShow) {
        if (policy == null) {
            return RefundAssessment.none(originalAmount);
        }
        Optional<RefundPolicyRule> matched = policy.ruleFor(hoursBeforeShow);
        if (matched.isEmpty()) {
            return RefundAssessment.none(originalAmount);
        }

        RefundPolicyRule rule = matched.get();
        BigDecimal percentage = rule.getRefundPercentage();
        BigDecimal amount = Money.capped(Money.percentageOf(originalAmount, percentage), originalAmount);
        return new RefundAssessment(policy, rule, percentage, Money.normalize(originalAmount), amount);
    }
}
