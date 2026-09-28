package com.harshil.movieticketbooking.refund.service;

import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import com.harshil.movieticketbooking.refund.domain.RefundPolicyRule;
import java.math.BigDecimal;

/**
 * What a refund policy decided, with the reasoning attached.
 * <p>
 * The matched policy and band travel with the amount so they can be persisted
 * onto the {@code refunds} row. Policies are editable, so without recording
 * which band applied, a refund issued today could not be explained after the
 * policy changes tomorrow.
 *
 * @param policy     the policy that was applied, null when none was configured
 * @param rule       the band that matched, null when none did
 * @param percentage the percentage applied, 0 - 100
 */
public record RefundAssessment(
        RefundPolicy policy,
        RefundPolicyRule rule,
        BigDecimal percentage,
        BigDecimal originalAmount,
        BigDecimal refundAmount) {

    /** No policy, or no band covering the elapsed time: nothing is refunded. */
    public static RefundAssessment none(BigDecimal originalAmount) {
        return new RefundAssessment(
                null, null, Money.ZERO, Money.normalize(originalAmount), Money.ZERO);
    }

    public boolean isRefundable() {
        return Money.isPositive(refundAmount);
    }
}
