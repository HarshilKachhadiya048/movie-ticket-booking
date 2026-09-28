package com.harshil.movieticketbooking.discount.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Discount arithmetic and validity rules, with no database involved. */
class DiscountCodeTest {

    private static final Instant NOW = Instant.parse("2026-03-12T09:00:00Z");

    private static DiscountCode.DiscountCodeBuilder code(DiscountType type, String value) {
        return DiscountCode.builder()
                .code("TEST")
                .discountType(type)
                .discountValue(new BigDecimal(value))
                .minBookingAmount(BigDecimal.ZERO)
                .validFrom(NOW.minus(Duration.ofDays(1)))
                .validUntil(NOW.plus(Duration.ofDays(1)))
                .usedCount(0)
                .active(true);
    }

    @Nested
    @DisplayName("amount calculation")
    class AmountCalculation {

        @Test
        void percentageAppliesToTheSubtotal() {
            DiscountCode discount = code(DiscountType.PERCENTAGE, "25").build();
            assertThat(discount.discountFor(new BigDecimal("800.00"))).isEqualByComparingTo("200.00");
        }

        @Test
        void fixedAmountIsIndependentOfTheSubtotal() {
            DiscountCode discount = code(DiscountType.FIXED_AMOUNT, "100").build();
            assertThat(discount.discountFor(new BigDecimal("800.00"))).isEqualByComparingTo("100.00");
        }

        @Test
        void theConfiguredCapIsApplied() {
            DiscountCode discount = code(DiscountType.PERCENTAGE, "50")
                    .maxDiscountAmount(new BigDecimal("150.00")).build();
            assertThat(discount.discountFor(new BigDecimal("1000.00"))).isEqualByComparingTo("150.00");
        }

        /**
         * A fixed discount worth more than the booking makes it free rather
         * than producing a negative total, which the {@code total >= 0} check
         * constraint would reject anyway.
         */
        @Test
        void discountNeverExceedsTheSubtotal() {
            DiscountCode discount = code(DiscountType.FIXED_AMOUNT, "500").build();
            assertThat(discount.discountFor(new BigDecimal("300.00"))).isEqualByComparingTo("300.00");
        }

        @Test
        void aHundredPercentDiscountMakesTheBookingFree() {
            DiscountCode discount = code(DiscountType.PERCENTAGE, "100").build();
            assertThat(discount.discountFor(new BigDecimal("640.00"))).isEqualByComparingTo("640.00");
        }
    }

    @Nested
    @DisplayName("validity window")
    class ValidityWindow {

        /** validFrom is inclusive, validUntil exclusive. */
        @Test
        void boundariesAreHalfOpen() {
            DiscountCode discount = code(DiscountType.PERCENTAGE, "10")
                    .validFrom(NOW)
                    .validUntil(NOW.plus(Duration.ofHours(1)))
                    .build();

            assertThat(discount.isWithinValidityWindow(NOW.minusMillis(1))).isFalse();
            assertThat(discount.isWithinValidityWindow(NOW)).isTrue();
            assertThat(discount.isWithinValidityWindow(NOW.plus(Duration.ofMinutes(59)))).isTrue();
            assertThat(discount.isWithinValidityWindow(NOW.plus(Duration.ofHours(1)))).isFalse();
        }
    }

    @Nested
    @DisplayName("usage limits")
    class UsageLimits {

        @Test
        void aNullLimitMeansUnlimited() {
            DiscountCode discount = code(DiscountType.PERCENTAGE, "10").usageLimit(null).usedCount(9_999).build();
            assertThat(discount.hasRemainingUses()).isTrue();
        }

        @Test
        void redemptionsCountTowardsTheLimit() {
            DiscountCode discount = code(DiscountType.PERCENTAGE, "10").usageLimit(2).build();

            assertThat(discount.hasRemainingUses()).isTrue();
            discount.recordRedemption();
            assertThat(discount.hasRemainingUses()).isTrue();
            discount.recordRedemption();
            assertThat(discount.hasRemainingUses()).isFalse();
        }

        /**
         * Releasing puts the code back in circulation, which is what happens
         * when a hold lapses or a payment is declined.
         */
        @Test
        void releasingARedemptionRestoresCapacity() {
            DiscountCode discount = code(DiscountType.PERCENTAGE, "10").usageLimit(1).build();
            discount.recordRedemption();
            assertThat(discount.hasRemainingUses()).isFalse();

            discount.releaseRedemption();

            assertThat(discount.hasRemainingUses()).isTrue();
            assertThat(discount.getUsedCount()).isZero();
        }

        /**
         * Release is reached from several paths that can legitimately overlap,
         * so it must never drive the counter below zero - the
         * {@code used_count >= 0} check constraint would reject that.
         */
        @Test
        void releasingMoreThanWasUsedCannotGoNegative() {
            DiscountCode discount = code(DiscountType.PERCENTAGE, "10").build();

            discount.releaseRedemption();
            discount.releaseRedemption();

            assertThat(discount.getUsedCount()).isZero();
        }
    }

    @Test
    void minimumBookingAmountIsInclusive() {
        DiscountCode discount = code(DiscountType.PERCENTAGE, "10")
                .minBookingAmount(new BigDecimal("500.00")).build();

        assertThat(discount.meetsMinimumBookingAmount(new BigDecimal("499.99"))).isFalse();
        assertThat(discount.meetsMinimumBookingAmount(new BigDecimal("500.00"))).isTrue();
        assertThat(discount.meetsMinimumBookingAmount(new BigDecimal("500.01"))).isTrue();
    }
}
