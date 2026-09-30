package com.harshil.movieticketbooking.seat.domain;

/**
 * Pricing tier of a physical seat.
 * <p>
 * One half of the key that {@code pricing_rules} is looked up by - the other
 * being {@link com.harshil.movieticketbooking.pricing.domain.DayType}. The
 * enum carries no prices: adding a tier means adding a constant here, a value
 * to the {@code ck_seats_category} check constraint and the matching rows to
 * {@code pricing_rules}, with no pricing code to change.
 */
public enum SeatCategory {

    REGULAR,
    PREMIUM
}
