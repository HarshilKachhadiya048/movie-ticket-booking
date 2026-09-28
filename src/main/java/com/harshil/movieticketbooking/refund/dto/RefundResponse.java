package com.harshil.movieticketbooking.refund.dto;

import com.harshil.movieticketbooking.refund.domain.Refund;
import com.harshil.movieticketbooking.refund.domain.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A refund, including why it came to the amount it did.
 * <p>
 * {@code policyName}, {@code refundPercentage} and {@code hoursBeforeShow} are
 * returned together so a customer can see the reasoning rather than just a
 * number - "cancelled 30.5 hours before the show, 100% band of the Standard
 * policy" is self-explaining in a way that a bare amount is not.
 */
public record RefundResponse(
        UUID refundId,
        RefundStatus status,
        BigDecimal originalAmount,
        BigDecimal refundPercentage,
        BigDecimal refundAmount,
        String currency,
        BigDecimal hoursBeforeShow,
        String policyName,
        String gatewayReference,
        String failureReason,
        Instant processedAt) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getStatus(),
                refund.getOriginalAmount(),
                refund.getRefundPercentage(),
                refund.getRefundAmount(),
                refund.getCurrency(),
                refund.getHoursBeforeShow(),
                refund.getRefundPolicy() == null ? null : refund.getRefundPolicy().getName(),
                refund.getGatewayReference(),
                refund.getFailureReason(),
                refund.getProcessedAt());
    }
}
