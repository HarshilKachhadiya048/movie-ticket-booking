package com.harshil.movieticketbooking.show.domain;

/**
 * Allocation state of a single seat for a single show.
 * <p>
 * The stored value is not the whole story: a {@link #HELD} row whose
 * {@code hold_expires_at} has passed is treated as {@link #AVAILABLE} by
 * {@link ShowSeat#effectiveStatus}. That lazy evaluation, performed while the
 * row is locked, is what makes expired holds reusable immediately rather than
 * whenever the cleanup scheduler next happens to run.
 */
public enum ShowSeatStatus {

    AVAILABLE,
    HELD,
    BOOKED
}
