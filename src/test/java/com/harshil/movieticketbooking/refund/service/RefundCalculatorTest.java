package com.harshil.movieticketbooking.refund.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import com.harshil.movieticketbooking.refund.domain.RefundPolicyRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The refund ladder, exhaustively, with no database and no clock.
 * <p>
 * The policy under test - 100% beyond 24h, 50% between 12h and 24h, nothing
 * inside 12h - is expressed as data, which is the point: these numbers live in
 * {@code refund_policy_rules}, not in the calculator.
 */
class RefundCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-03-12T09:00:00Z");
    private static final BigDecimal PAID = new BigDecimal("1000.00");

    private final RefundCalculator calculator = new RefundCalculator();

    private static RefundPolicy standardPolicy() {
        RefundPolicy policy = RefundPolicy.builder()
                .name("Standard")
                .defaultPolicy(true)
                .active(true)
                .build();
        policy.addRule(band(0, 12, "0.00"));
        policy.addRule(band(12, 24, "50.00"));
        policy.addRule(band(24, null, "100.00"));
        return policy;
    }

    private static RefundPolicyRule band(int min, Integer max, String percentage) {
        return RefundPolicyRule.builder()
                .minHoursBeforeShow(min)
                .maxHoursBeforeShow(max)
                .refundPercentage(new BigDecimal(percentage))
                .build();
    }

    @ParameterizedTest(name = "{0}h before the show -> {1}% -> {2}")
    @CsvSource({
            "48,  100, 1000.00",
            "24,  100, 1000.00",
            "23,   50,  500.00",
            "12,   50,  500.00",
            "11,    0,    0.00",
            "0,     0,    0.00"
    })
    void eachBandIsApplied(int hoursBefore, String expectedPercentage, String expectedAmount) {
        BigDecimal hours = BigDecimal.valueOf(hoursBefore).setScale(2);

        RefundAssessment assessment = calculator.assess(standardPolicy(), PAID, hours);

        assertThat(assessment.percentage()).isEqualByComparingTo(expectedPercentage);
        assertThat(assessment.refundAmount()).isEqualByComparingTo(expectedAmount);
    }

    @Nested
    @DisplayName("band boundaries")
    class Boundaries {

        /**
         * Bands are half-open, so the boundary belongs to the band above it.
         * A cancellation at exactly 24h gets the full refund; one a minute
         * later gets half. Without this, 24h would match two bands and the
         * answer would depend on row order.
         */
        @Test
        void boundaryBelongsToTheHigherBand() {
            assertThat(calculator.assess(standardPolicy(), PAID, new BigDecimal("24.00")).percentage())
                    .isEqualByComparingTo("100.00");
            assertThat(calculator.assess(standardPolicy(), PAID, new BigDecimal("23.99")).percentage())
                    .isEqualByComparingTo("50.00");
        }

        /**
         * Hours are computed from seconds, not rounded to whole hours, so
         * 23h59m stays in the 50% band instead of being rounded up into the
         * 100% one.
         */
        @Test
        void hoursAreNotRoundedUpAcrossABoundary() {
            Instant showStart = NOW.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(59));

            BigDecimal hours = calculator.hoursUntil(showStart, NOW);

            assertThat(hours).isEqualByComparingTo("23.98");
            assertThat(calculator.assess(standardPolicy(), PAID, hours).percentage())
                    .isEqualByComparingTo("50.00");
        }
    }

    @Nested
    @DisplayName("failing closed")
    class FailingClosed {

        /** No policy configured must mean no refund, never a full one. */
        @Test
        void noPolicyMeansNoRefund() {
            RefundAssessment assessment = calculator.assess(null, PAID, new BigDecimal("48.00"));

            assertThat(assessment.refundAmount()).isEqualByComparingTo("0.00");
            assertThat(assessment.isRefundable()).isFalse();
            assertThat(assessment.policy()).isNull();
        }

        /** A gap in the ladder behaves the same way: under-refund, visibly. */
        @Test
        void aGapInTheLadderMeansNoRefund() {
            RefundPolicy gapped = RefundPolicy.builder().name("Gapped").active(true).build();
            gapped.addRule(band(48, null, "100.00"));

            RefundAssessment assessment = calculator.assess(gapped, PAID, new BigDecimal("10.00"));

            assertThat(assessment.refundAmount()).isEqualByComparingTo("0.00");
            assertThat(assessment.rule()).isNull();
        }

        /** Past the show start, hours go negative and nothing matches. */
        @Test
        void afterTheShowHasStartedNothingIsRefunded() {
            BigDecimal hours = calculator.hoursUntil(NOW.minus(Duration.ofHours(2)), NOW);

            assertThat(hours).isNegative();
            assertThat(calculator.assess(standardPolicy(), PAID, hours).refundAmount())
                    .isEqualByComparingTo("0.00");
        }
    }

    @Test
    void refundNeverExceedsWhatWasPaid() {
        RefundPolicy generous = RefundPolicy.builder().name("Generous").active(true).build();
        generous.addRule(band(0, null, "100.00"));

        RefundAssessment assessment = calculator.assess(generous, new BigDecimal("0.01"), new BigDecimal("1.00"));

        assertThat(assessment.refundAmount()).isEqualByComparingTo("0.01");
    }

    @Test
    void hoursUntilIsExactToTwoDecimalPlaces() {
        assertThat(calculator.hoursUntil(NOW.plus(Duration.ofMinutes(90)), NOW))
                .isEqualByComparingTo("1.50");
        assertThat(calculator.hoursUntil(NOW.plus(Duration.ofSeconds(1)), NOW))
                .isEqualByComparingTo("0.00");
    }
}
