package com.harshil.movieticketbooking.pricing.dto;

import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Create or update a pricing rule.
 * <p>
 * At most one of {@code cityId}, {@code theaterId} and {@code screenId} may be
 * set; that single value is the rule's scope. Setting none makes it the global
 * default for the seat category and day type. The service rejects a request
 * that sets more than one, and the database enforces the same thing through
 * {@code ck_pricing_rules_single_scope}.
 *
 * @param price the exact amount charged, not a multiplier. Weekend pricing is
 *              a separate rule with a different price, not a factor applied to
 *              the weekday one, so no arithmetic is hidden in code.
 */
public record PricingRuleRequest(
        UUID cityId,
        UUID theaterId,
        UUID screenId,

        @NotNull(message = "seatCategory is required") SeatCategory seatCategory,
        @NotNull(message = "dayType is required") DayType dayType,

        @NotNull(message = "price is required")
        @PositiveOrZero(message = "price must not be negative")
        @Digits(integer = 8, fraction = 2, message = "price must have at most 2 decimal places")
        BigDecimal price,

        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }

    public int scopeCount() {
        return (cityId == null ? 0 : 1) + (theaterId == null ? 0 : 1) + (screenId == null ? 0 : 1);
    }
}
