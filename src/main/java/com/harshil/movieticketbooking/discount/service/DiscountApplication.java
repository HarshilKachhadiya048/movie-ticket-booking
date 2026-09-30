package com.harshil.movieticketbooking.discount.service;

import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import java.math.BigDecimal;

/**
 * A validated, locked discount ready to be attached to a booking.
 * <p>
 * Holding the {@link DiscountCode} entity rather than just its id is
 * intentional: the instance was loaded under a pessimistic row lock by
 * {@link DiscountService#evaluate}, and the caller reuses it within the same
 * transaction, so the redemption that follows still runs under that lock.
 *
 * @param discountCode   the locked code entity
 * @param discountAmount the amount to subtract, already capped and normalised
 */
public record DiscountApplication(DiscountCode discountCode, BigDecimal discountAmount) {
}
