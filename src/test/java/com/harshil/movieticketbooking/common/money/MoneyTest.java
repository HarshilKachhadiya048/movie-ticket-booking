package com.harshil.movieticketbooking.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @Test
        void roundsHalfUpToTwoDecimalPlaces() {
            assertThat(Money.normalize(new BigDecimal("10.005"))).isEqualByComparingTo("10.01");
            assertThat(Money.normalize(new BigDecimal("10.004"))).isEqualByComparingTo("10.00");
        }

        @Test
        void treatsNullAsZero() {
            assertThat(Money.normalize(null)).isEqualByComparingTo("0.00");
        }

        @Test
        void alwaysProducesScaleTwo() {
            assertThat(Money.normalize(new BigDecimal("7")).scale()).isEqualTo(2);
        }
    }

    /**
     * The reason normalisation happens before arithmetic rather than after.
     * {@code bookings} carries the check constraint
     * {@code total = subtotal - discount}, and PostgreSQL rounds on store. If
     * the total were computed from unrounded inputs, the row would fail that
     * constraint for inputs like these.
     */
    @Test
    @DisplayName("subtract stays consistent with what the database will store")
    void subtractMatchesStoredValues() {
        BigDecimal subtotal = new BigDecimal("100.00");
        BigDecimal unroundedDiscount = new BigDecimal("10.005");

        BigDecimal storedDiscount = Money.normalize(unroundedDiscount);
        BigDecimal total = Money.subtract(subtotal, unroundedDiscount);

        assertThat(storedDiscount).isEqualByComparingTo("10.01");
        assertThat(total).isEqualByComparingTo("89.99");
        assertThat(subtotal.subtract(storedDiscount)).isEqualByComparingTo(total);
    }

    @ParameterizedTest(name = "{1}% of {0} = {2}")
    @CsvSource({
            "1000.00, 50, 500.00",
            "1000.00, 0, 0.00",
            "1000.00, 100, 1000.00",
            "333.33, 10, 33.33",
            "333.35, 50, 166.68"
    })
    void percentageOfRoundsHalfUp(String amount, String percentage, String expected) {
        assertThat(Money.percentageOf(new BigDecimal(amount), new BigDecimal(percentage)))
                .isEqualByComparingTo(expected);
    }

    @Nested
    @DisplayName("capped")
    class Capped {

        @Test
        void clampsToTheCeiling() {
            assertThat(Money.capped(new BigDecimal("500.00"), new BigDecimal("150.00")))
                    .isEqualByComparingTo("150.00");
        }

        @Test
        void leavesValuesBelowTheCeilingAlone() {
            assertThat(Money.capped(new BigDecimal("80.00"), new BigDecimal("150.00")))
                    .isEqualByComparingTo("80.00");
        }

        /** A discount can never make a booking total negative. */
        @Test
        void clampsNegativesToZero() {
            assertThat(Money.capped(new BigDecimal("-25.00"), new BigDecimal("150.00")))
                    .isEqualByComparingTo("0.00");
        }
    }

    @Test
    void multiplyScalesByAWholeFactor() {
        assertThat(Money.multiply(new BigDecimal("199.99"), 3)).isEqualByComparingTo("599.97");
    }

    @Test
    void isPositiveAndIsZeroAreNullSafe() {
        assertThat(Money.isPositive(null)).isFalse();
        assertThat(Money.isZero(null)).isTrue();
        assertThat(Money.isPositive(new BigDecimal("0.00"))).isFalse();
        assertThat(Money.isZero(new BigDecimal("0.00"))).isTrue();
        assertThat(Money.isPositive(new BigDecimal("0.01"))).isTrue();
    }
}
