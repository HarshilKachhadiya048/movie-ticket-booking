package com.harshil.movieticketbooking.discount.dto;

import com.harshil.movieticketbooking.discount.domain.DiscountType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Create or update a discount code.
 * <p>
 * Cross-field rules - a percentage over 100, a validity window that ends
 * before it starts - are checked in the service and mirrored by check
 * constraints on the table, because bean validation on a record cannot express
 * a rule spanning two components without a custom validator that would earn
 * its keep only here.
 *
 * @param usageLimit    total redemptions allowed; null means unlimited
 * @param perUserLimit  redemptions allowed per customer; null means unlimited
 */
public record DiscountCodeRequest(
        @NotBlank(message = "code is required")
        @Size(max = 40, message = "code must not exceed 40 characters")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "code may only contain letters, digits, hyphen and underscore")
        String code,

        @Size(max = 255, message = "description must not exceed 255 characters") String description,

        @NotNull(message = "discountType is required") DiscountType discountType,

        @NotNull(message = "discountValue is required")
        @Positive(message = "discountValue must be positive")
        @Digits(integer = 8, fraction = 2, message = "discountValue must have at most 2 decimal places")
        BigDecimal discountValue,

        @Positive(message = "maxDiscountAmount must be positive")
        @Digits(integer = 8, fraction = 2, message = "maxDiscountAmount must have at most 2 decimal places")
        BigDecimal maxDiscountAmount,

        @PositiveOrZero(message = "minBookingAmount must not be negative")
        @Digits(integer = 8, fraction = 2, message = "minBookingAmount must have at most 2 decimal places")
        BigDecimal minBookingAmount,

        @NotNull(message = "validFrom is required") Instant validFrom,
        @NotNull(message = "validUntil is required") Instant validUntil,

        @Positive(message = "usageLimit must be positive") Integer usageLimit,
        @Positive(message = "perUserLimit must be positive") Integer perUserLimit,

        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }

    public BigDecimal minBookingAmountOrZero() {
        return minBookingAmount == null ? BigDecimal.ZERO : minBookingAmount;
    }
}
