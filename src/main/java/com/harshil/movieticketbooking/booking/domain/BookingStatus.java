package com.harshil.movieticketbooking.booking.domain;

import java.util.Set;

/**
 * The booking state machine.
 * <p>
 * <pre>
 *                    payment initiated            gateway approved
 *   HOLD_CREATED ──────────────────────▶ PAYMENT_PENDING ───────────────▶ CONFIRMED
 *        │                                      │                              │
 *        │ cancelled / hold lapsed              │ declined                     │ cancelled
 *        ▼                                      ▼                              ▼
 *   CANCELLED | EXPIRED                   PAYMENT_FAILED           CANCELLED | REFUNDED
 * </pre>
 * <p>
 * Notes on the design:
 * <ul>
 *     <li><b>EXPIRED is distinct from CANCELLED.</b> The brief lists CANCELLED;
 *     splitting out the hold that simply lapsed keeps "the customer changed
 *     their mind" separate from "the customer never paid", which matters for
 *     reporting and means a cancellation always implies a deliberate act.</li>
 *     <li><b>PAYMENT_FAILED is terminal.</b> A declined payment releases the
 *     seats, so there is nothing left to retry against - the seats may already
 *     belong to somebody else. Retrying means taking a fresh hold, which is
 *     honest about what actually has to happen.</li>
 *     <li><b>REFUNDED implies cancelled.</b> A cancellation that returns money
 *     lands in REFUNDED, one that returns nothing lands in CANCELLED. Both are
 *     terminal; the {@code refunds} row carries the detail.</li>
 * </ul>
 * Transitions are enforced by {@link Booking#transitionTo}, so no caller can
 * move a booking sideways through the graph.
 */
public enum BookingStatus {

    /** Seats are held. The customer has not paid yet. */
    HOLD_CREATED,

    /** A payment attempt is in flight at the gateway. */
    PAYMENT_PENDING,

    /** Paid. The seats are BOOKED. */
    CONFIRMED,

    /** The gateway declined. Seats have been released. Terminal. */
    PAYMENT_FAILED,

    /** Cancelled with no money returned. Terminal. */
    CANCELLED,

    /** Cancelled with a refund issued. Terminal. */
    REFUNDED,

    /** The hold lapsed before payment completed. Terminal. */
    EXPIRED;

    public Set<BookingStatus> allowedTransitions() {
        return switch (this) {
            case HOLD_CREATED -> Set.of(PAYMENT_PENDING, CANCELLED, EXPIRED);
            case PAYMENT_PENDING -> Set.of(CONFIRMED, PAYMENT_FAILED, EXPIRED);
            case CONFIRMED -> Set.of(CANCELLED, REFUNDED);
            case PAYMENT_FAILED, CANCELLED, REFUNDED, EXPIRED -> Set.of();
        };
    }

    public boolean canTransitionTo(BookingStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /** True while the booking is still relying on a live seat hold. */
    public boolean holdsSeats() {
        return this == HOLD_CREATED || this == PAYMENT_PENDING;
    }

    /** True when the booking's seats are permanently allocated to it. */
    public boolean ownsSeats() {
        return this == CONFIRMED;
    }
}
