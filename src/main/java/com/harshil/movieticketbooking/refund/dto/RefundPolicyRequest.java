package com.harshil.movieticketbooking.refund.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Create or replace a refund policy and its bands.
 * <p>
 * The brief's example ladder is expressed here as data:
 * <pre>
 *   { "minHoursBeforeShow": 0,  "maxHoursBeforeShow": 12,   "refundPercentage": 0 }
 *   { "minHoursBeforeShow": 12, "maxHoursBeforeShow": 24,   "refundPercentage": 50 }
 *   { "minHoursBeforeShow": 24, "maxHoursBeforeShow": null, "refundPercentage": 100 }
 * </pre>
 *
 * @param theaterId  scopes the policy to one venue; null makes it a candidate
 *                   for the platform default
 * @param isDefault  marks this the platform default. Only one policy may hold
 *                   that flag, enforced by a partial unique index, and a
 *                   default may not also be theater-scoped.
 */
public record RefundPolicyRequest(
        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must not exceed 120 characters") String name,

        @Size(max = 255, message = "description must not exceed 255 characters") String description,

        UUID theaterId,
        Boolean isDefault,
        Boolean active,

        @NotEmpty(message = "At least one refund band is required")
        @Valid List<RefundPolicyRuleRequest> rules) {

    public boolean activeOrDefault() {
        return active == null || active;
    }

    public boolean defaultPolicyOrFalse() {
        return isDefault != null && isDefault;
    }

    /**
     * One half-open band, {@code [min, max)}.
     *
     * @param maxHoursBeforeShow null means the band extends indefinitely,
     *                           which is how the most generous tier is written
     */
    public record RefundPolicyRuleRequest(
            @NotNull(message = "minHoursBeforeShow is required")
            @PositiveOrZero(message = "minHoursBeforeShow must not be negative") Integer minHoursBeforeShow,

            @PositiveOrZero(message = "maxHoursBeforeShow must not be negative") Integer maxHoursBeforeShow,

            @NotNull(message = "refundPercentage is required")
            @DecimalMin(value = "0", message = "refundPercentage must not be negative")
            @DecimalMax(value = "100", message = "refundPercentage must not exceed 100")
            BigDecimal refundPercentage) {
    }
}
