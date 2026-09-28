package com.harshil.movieticketbooking.show.domain;

/** Lifecycle of a scheduled screening. */
public enum ShowStatus {

    /** Open for booking, provided it has not started yet. */
    SCHEDULED,

    /** Called off by an admin. No new holds; existing bookings are refundable. */
    CANCELLED,

    /** Already played. Retained for booking history. */
    COMPLETED;

    public boolean isOpenForBooking() {
        return this == SCHEDULED;
    }
}
