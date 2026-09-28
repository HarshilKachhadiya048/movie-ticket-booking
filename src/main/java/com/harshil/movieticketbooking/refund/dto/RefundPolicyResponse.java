package com.harshil.movieticketbooking.refund.dto;

import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import com.harshil.movieticketbooking.refund.domain.RefundPolicyRule;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RefundPolicyResponse(
        UUID refundPolicyId,
        String name,
        String description,
        UUID theaterId,
        boolean isDefault,
        boolean active,
        List<RefundBandResponse> bands) {

    public static RefundPolicyResponse from(RefundPolicy policy) {
        return new RefundPolicyResponse(
                policy.getId(),
                policy.getName(),
                policy.getDescription(),
                policy.getTheater() == null ? null : policy.getTheater().getId(),
                policy.isDefaultPolicy(),
                policy.isActive(),
                policy.getRules().stream().map(RefundBandResponse::from).toList());
    }

    /** One band of the ladder, {@code [min, max)} hours before the show. */
    public record RefundBandResponse(
            UUID bandId,
            int minHoursBeforeShow,
            Integer maxHoursBeforeShow,
            BigDecimal refundPercentage) {

        public static RefundBandResponse from(RefundPolicyRule rule) {
            return new RefundBandResponse(
                    rule.getId(),
                    rule.getMinHoursBeforeShow(),
                    rule.getMaxHoursBeforeShow(),
                    rule.getRefundPercentage());
        }
    }
}
