package com.harshil.movieticketbooking.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Monetary arithmetic helpers.
 * <p>
 * Every amount that reaches the database is normalised to scale 2 with
 * HALF_UP rounding <em>before</em> any further arithmetic. That is not
 * cosmetic: {@code bookings} carries the check constraint
 * {@code total_amount = subtotal_amount - discount_amount}, and PostgreSQL
 * rounds on store. Rounding a discount of {@code 10.005} to {@code 10.01} on
 * the way in while computing the total from the unrounded value would produce
 * {@code 89.995 -> 90.00} and violate the constraint. Normalising first keeps
 * the arithmetic the database sees identical to the arithmetic Java did.
 * <p>
 * Money is never represented as a floating point type anywhere in this
 * codebase.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING_MODE);
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    /** Rounds to the storable scale. Null-safe, returning a scaled zero. */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(SCALE, ROUNDING_MODE);
    }

    public static BigDecimal sum(BigDecimal left, BigDecimal right) {
        return normalize(normalize(left).add(normalize(right)));
    }

    public static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return normalize(normalize(left).subtract(normalize(right)));
    }

    public static BigDecimal multiply(BigDecimal amount, long factor) {
        return normalize(normalize(amount).multiply(BigDecimal.valueOf(factor)));
    }

    /** {@code percentage} is expressed as 0-100, not as a fraction. */
    public static BigDecimal percentageOf(BigDecimal amount, BigDecimal percentage) {
        if (amount == null || percentage == null) {
            return ZERO;
        }
        return normalize(
                normalize(amount)
                        .multiply(percentage)
                        .divide(HUNDRED, SCALE, ROUNDING_MODE));
    }

    /** Clamps to {@code [0, max]}. A discount can never exceed what is owed. */
    public static BigDecimal capped(BigDecimal amount, BigDecimal max) {
        BigDecimal normalized = normalize(amount);
        if (normalized.signum() < 0) {
            return ZERO;
        }
        BigDecimal ceiling = normalize(max);
        return normalized.compareTo(ceiling) > 0 ? ceiling : normalized;
    }

    public static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    public static boolean isZero(BigDecimal amount) {
        return amount == null || amount.signum() == 0;
    }
}
