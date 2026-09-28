package com.harshil.movieticketbooking.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The booking state machine as a graph: which moves exist, which do not, and
 * which states are dead ends.
 */
class BookingStatusTest {

    @Test
    @DisplayName("the whole transition table")
    void transitionTable() {
        assertThat(BookingStatus.HOLD_CREATED.allowedTransitions())
                .containsExactlyInAnyOrder(
                        BookingStatus.PAYMENT_PENDING, BookingStatus.CANCELLED, BookingStatus.EXPIRED);
        assertThat(BookingStatus.PAYMENT_PENDING.allowedTransitions())
                .containsExactlyInAnyOrder(
                        BookingStatus.CONFIRMED, BookingStatus.PAYMENT_FAILED, BookingStatus.EXPIRED);
        assertThat(BookingStatus.CONFIRMED.allowedTransitions())
                .containsExactlyInAnyOrder(BookingStatus.CANCELLED, BookingStatus.REFUNDED);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class,
            names = { "PAYMENT_FAILED", "CANCELLED", "REFUNDED", "EXPIRED" })
    void terminalStatesHaveNoWayOut(BookingStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedTransitions()).isEmpty();
    }

    /**
     * A booking cannot skip payment. The only route into CONFIRMED is through
     * PAYMENT_PENDING, which is what makes "confirmed implies a settled
     * payment attempt" true by construction.
     */
    @Test
    void confirmedIsOnlyReachableFromPaymentPending() {
        Set<BookingStatus> reachingConfirmed = EnumSet.noneOf(BookingStatus.class);
        for (BookingStatus status : BookingStatus.values()) {
            if (status.canTransitionTo(BookingStatus.CONFIRMED)) {
                reachingConfirmed.add(status);
            }
        }
        assertThat(reachingConfirmed).containsExactly(BookingStatus.PAYMENT_PENDING);
    }

    /**
     * A declined payment releases the seats, so there is nothing left to retry
     * against - they may already belong to somebody else. Retrying means a
     * fresh hold.
     */
    @Test
    void paymentFailedCannotBeRetriedInPlace() {
        assertThat(BookingStatus.PAYMENT_FAILED.canTransitionTo(BookingStatus.PAYMENT_PENDING)).isFalse();
        assertThat(BookingStatus.PAYMENT_FAILED.canTransitionTo(BookingStatus.CONFIRMED)).isFalse();
    }

    @Test
    void onlyActiveHoldStatesClaimSeats() {
        assertThat(BookingStatus.HOLD_CREATED.holdsSeats()).isTrue();
        assertThat(BookingStatus.PAYMENT_PENDING.holdsSeats()).isTrue();
        assertThat(BookingStatus.CONFIRMED.holdsSeats()).isFalse();
        assertThat(BookingStatus.CONFIRMED.ownsSeats()).isTrue();
        assertThat(BookingStatus.EXPIRED.holdsSeats()).isFalse();
        assertThat(BookingStatus.EXPIRED.ownsSeats()).isFalse();
    }
}
