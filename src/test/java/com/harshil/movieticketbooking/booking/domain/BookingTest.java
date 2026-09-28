package com.harshil.movieticketbooking.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Transition enforcement and hold bookkeeping on the booking aggregate. */
class BookingTest {

    private static final Instant NOW = Instant.parse("2026-03-12T09:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(5);

    private static Booking heldBooking() {
        return Booking.builder()
                .bookingReference("MTB-TEST00000001")
                .status(BookingStatus.HOLD_CREATED)
                .holdToken(UUID.randomUUID())
                .holdExpiresAt(NOW.plus(HOLD))
                .seatCount(2)
                .subtotalAmount(new BigDecimal("400.00"))
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("400.00"))
                .currency("INR")
                .build();
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        void happyPathReachesConfirmed() {
            Booking booking = heldBooking();

            booking.markPaymentPending();
            booking.confirm(NOW);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(booking.getConfirmedAt()).isEqualTo(NOW);
        }

        @Test
        void illegalMovesAreRejectedWithAConflictCode() {
            Booking booking = heldBooking();

            assertThatThrownBy(() -> booking.confirm(NOW))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.INVALID_BOOKING_STATE_TRANSITION);
        }

        @Test
        void terminalStatesCannotBeLeft() {
            Booking booking = heldBooking();
            booking.markPaymentPending();
            booking.markPaymentFailed();

            assertThatThrownBy(() -> booking.confirm(NOW)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> booking.cancel(NOW)).isInstanceOf(DomainException.class);
        }

        /**
         * Re-applying the state a booking is already in is a no-op rather than
         * an error. That is what lets the cancellation and payment paths be
         * safely retried.
         */
        @Test
        void transitioningToTheCurrentStateIsANoOp() {
            Booking booking = heldBooking();
            assertThatCode(() -> booking.transitionTo(BookingStatus.HOLD_CREATED)).doesNotThrowAnyException();
            assertThat(booking.getStatus()).isEqualTo(BookingStatus.HOLD_CREATED);
        }

        @Test
        void aHoldCanBeCancelledBeforePaymentStarts() {
            Booking booking = heldBooking();
            booking.cancel(NOW);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(booking.getCancelledAt()).isEqualTo(NOW);
        }

        @Test
        void refundedIsReachableOnlyFromConfirmed() {
            Booking booking = heldBooking();
            assertThatThrownBy(() -> booking.markRefunded(NOW)).isInstanceOf(DomainException.class);

            booking.markPaymentPending();
            booking.confirm(NOW);
            booking.markRefunded(NOW);

            assertThat(booking.getStatus()).isEqualTo(BookingStatus.REFUNDED);
        }
    }

    @Nested
    @DisplayName("hold bookkeeping")
    class HoldBookkeeping {

        @Test
        void holdIsLiveUntilItsDeadline() {
            Booking booking = heldBooking();

            assertThat(booking.isHoldLive(NOW)).isTrue();
            assertThat(booking.isHoldExpired(NOW)).isFalse();
            assertThat(booking.isHoldLive(NOW.plus(HOLD))).isFalse();
            assertThat(booking.isHoldExpired(NOW.plus(HOLD))).isTrue();
        }

        /**
         * Every terminal transition clears the hold columns, so a finished
         * booking can never look like it still claims seats.
         */
        @Test
        void reachingATerminalStateClearsTheHold() {
            Booking confirmed = heldBooking();
            confirmed.markPaymentPending();
            confirmed.confirm(NOW);
            assertThat(confirmed.getHoldToken()).isNull();
            assertThat(confirmed.getHoldExpiresAt()).isNull();

            Booking expired = heldBooking();
            expired.expire();
            assertThat(expired.getHoldToken()).isNull();
            assertThat(expired.getHoldExpiresAt()).isNull();

            Booking cancelled = heldBooking();
            cancelled.cancel(NOW);
            assertThat(cancelled.getHoldToken()).isNull();
        }
    }

    @Test
    void ownershipComparesTheOwningUser() {
        Booking booking = heldBooking();
        assertThat(booking.isOwnedBy(UUID.randomUUID())).isFalse();
    }
}
