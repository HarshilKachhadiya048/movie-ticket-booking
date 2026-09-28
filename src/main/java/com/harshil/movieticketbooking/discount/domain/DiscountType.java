package com.harshil.movieticketbooking.discount.domain;

import com.harshil.movieticketbooking.common.money.Money;
import java.math.BigDecimal;

/**
 * How a discount code's value is interpreted.
 * <p>
 * The arithmetic lives on the enum constant rather than in a switch inside the
 * service, so adding a scheme means adding a constant and its rule here - the
 * one place that already has to change.
 */
public enum DiscountType {

    /** {@code value} is a percentage of the subtotal, 0 - 100. */
    PERCENTAGE {
        @Override
        public BigDecimal rawDiscountOn(BigDecimal subtotal, BigDecimal value) {
            return Money.percentageOf(subtotal, value);
        }
    },

    /** {@code value} is a flat amount in the booking currency. */
    FIXED_AMOUNT {
        @Override
        public BigDecimal rawDiscountOn(BigDecimal subtotal, BigDecimal value) {
            return Money.normalize(value);
        }
    };

    /**
     * The discount before the code's own {@code maxDiscountAmount} cap and
     * before clamping to the subtotal. Both are applied by
     * {@link DiscountCode#discountFor(BigDecimal)}.
     */
    public abstract BigDecimal rawDiscountOn(BigDecimal subtotal, BigDecimal value);
}
