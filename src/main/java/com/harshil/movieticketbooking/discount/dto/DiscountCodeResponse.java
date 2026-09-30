package com.harshil.movieticketbooking.discount.dto;

import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import com.harshil.movieticketbooking.discount.domain.DiscountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A discount code with its limits and current consumption.
 * <p>
 * {@code usedCount} counts live redemptions only. A hold that lapsed, a
 * payment that was declined or a booking that was cancelled all return their
 * redemption, so this figure tracks codes actually in use rather than
 * attempts ever made.
 */
public record DiscountCodeResponse(
        UUID discountCodeId,
        String code,
        String description,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal minBookingAmount,
        Instant validFrom,
        Instant validUntil,
        Integer usageLimit,
        Integer perUserLimit,
        int usedCount,
        boolean active) {

    public static DiscountCodeResponse from(DiscountCode discountCode) {
        return new DiscountCodeResponse(
                discountCode.getId(),
                discountCode.getCode(),
                discountCode.getDescription(),
                discountCode.getDiscountType(),
                discountCode.getDiscountValue(),
                discountCode.getMaxDiscountAmount(),
                discountCode.getMinBookingAmount(),
                discountCode.getValidFrom(),
                discountCode.getValidUntil(),
                discountCode.getUsageLimit(),
                discountCode.getPerUserLimit(),
                discountCode.getUsedCount(),
                discountCode.isActive());
    }
}
