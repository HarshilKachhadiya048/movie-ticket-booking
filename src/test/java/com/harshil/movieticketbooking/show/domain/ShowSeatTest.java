package com.harshil.movieticketbooking.show.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Hold expiry and the release-safety rules, tested without a database.
 * <p>
 * These are the invariants the concurrency guarantee is built on top of: the
 * database serialises access to the row, and this class decides what that row
 * means once you have it.
 */
class ShowSeatTest {

    private static final Instant NOW = Instant.parse("2026-03-12T09:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(5);

    private static ShowSeat availableSeat() {
        return ShowSeat.builder().status(ShowSeatStatus.AVAILABLE).build();
    }

    @Nested
    @DisplayName("lazy hold expiry")
    class LazyExpiry {

        /**
         * The single most important behaviour in the class. A row still marked
         * HELD whose expiry has passed must read as AVAILABLE, because that is
         * what makes an expired hold re-bookable without waiting for the
         * cleanup scheduler.
         */
        @Test
        void expiredHoldReadsAsAvailable() {
            ShowSeat seat = availableSeat();
            seat.hold(UUID.randomUUID(), UUID.randomUUID(), NOW.plus(HOLD), NOW);

            Instant afterExpiry = NOW.plus(HOLD).plusSeconds(1);

            assertThat(seat.getStatus()).isEqualTo(ShowSeatStatus.HELD);
            assertThat(seat.effectiveStatus(afterExpiry)).isEqualTo(ShowSeatStatus.AVAILABLE);
            assertThat(seat.isAvailableAt(afterExpiry)).isTrue();
        }

        @Test
        void liveHoldReadsAsHeld() {
            ShowSeat seat = availableSeat();
            seat.hold(UUID.randomUUID(), UUID.randomUUID(), NOW.plus(HOLD), NOW);

            Instant beforeExpiry = NOW.plus(Duration.ofMinutes(4));

            assertThat(seat.effectiveStatus(beforeExpiry)).isEqualTo(ShowSeatStatus.HELD);
            assertThat(seat.isAvailableAt(beforeExpiry)).isFalse();
        }

        /**
         * Expiry is inclusive of the boundary: at exactly {@code expiresAt} the
         * hold is over. Anything else would leave an ambiguous instant where
         * two transactions could disagree about who owns the seat.
         */
        @Test
        void holdExpiresExactlyAtItsDeadline() {
            ShowSeat seat = availableSeat();
            Instant expiresAt = NOW.plus(HOLD);
            seat.hold(UUID.randomUUID(), UUID.randomUUID(), expiresAt, NOW);

            assertThat(seat.isHoldExpired(expiresAt.minusMillis(1))).isFalse();
            assertThat(seat.isHoldExpired(expiresAt)).isTrue();
        }

        /** Booked is permanent; expiry has nothing to say about it. */
        @Test
        void bookedSeatNeverBecomesAvailable() {
            ShowSeat seat = availableSeat();
            seat.hold(UUID.randomUUID(), UUID.randomUUID(), NOW.plus(HOLD), NOW);
            seat.confirm();

            assertThat(seat.effectiveStatus(NOW.plus(Duration.ofDays(365))))
                    .isEqualTo(ShowSeatStatus.BOOKED);
        }
    }

    @Nested
    @DisplayName("hold ownership")
    class HoldOwnership {

        @Test
        void isValidHoldRequiresMatchingTokenAndLiveExpiry() {
            ShowSeat seat = availableSeat();
            UUID token = UUID.randomUUID();
            seat.hold(token, UUID.randomUUID(), NOW.plus(HOLD), NOW);

            assertThat(seat.isValidHold(token, NOW)).isTrue();
            assertThat(seat.isValidHold(UUID.randomUUID(), NOW)).isFalse();
            assertThat(seat.isValidHold(null, NOW)).isFalse();
            assertThat(seat.isValidHold(token, NOW.plus(HOLD))).isFalse();
        }

        /**
         * The distinction that stops the release paths stealing seats. Once a
         * hold lapses the seat may be taken by somebody else through lazy
         * expiry; the new hold carries a different token, so releasing must
         * test the token, not the status.
         */
        @Test
        void isHeldUnderIgnoresExpiryButNotTheToken() {
            ShowSeat seat = availableSeat();
            UUID token = UUID.randomUUID();
            seat.hold(token, UUID.randomUUID(), NOW.plus(HOLD), NOW);

            assertThat(seat.isHeldUnder(token)).isTrue();
            assertThat(seat.isValidHold(token, NOW.plus(Duration.ofHours(1)))).isFalse();
            assertThat(seat.isHeldUnder(UUID.randomUUID())).isFalse();
        }

        @Test
        void reHoldingAnExpiredSeatReplacesTheOwner() {
            ShowSeat seat = availableSeat();
            UUID firstToken = UUID.randomUUID();
            seat.hold(firstToken, UUID.randomUUID(), NOW.plus(HOLD), NOW);

            Instant later = NOW.plus(Duration.ofMinutes(6));
            UUID secondToken = UUID.randomUUID();
            UUID secondUser = UUID.randomUUID();
            seat.hold(secondToken, secondUser, later.plus(HOLD), later);

            assertThat(seat.isHeldUnder(firstToken)).isFalse();
            assertThat(seat.isValidHold(secondToken, later)).isTrue();
            assertThat(seat.getHeldByUserId()).isEqualTo(secondUser);
        }
    }

    @Nested
    @DisplayName("hold column invariants")
    class HoldColumns {

        /**
         * {@code ck_show_seats_hold_fields} requires hold columns to be all
         * set or all null. Both transitions off HELD must therefore clear
         * every one of them, or the database rejects the row.
         */
        @Test
        void confirmClearsEveryHoldColumn() {
            ShowSeat seat = availableSeat();
            seat.hold(UUID.randomUUID(), UUID.randomUUID(), NOW.plus(HOLD), NOW);
            seat.confirm();

            assertThat(seat.getStatus()).isEqualTo(ShowSeatStatus.BOOKED);
            assertThat(seat.getHoldToken()).isNull();
            assertThat(seat.getHeldByUserId()).isNull();
            assertThat(seat.getHoldExpiresAt()).isNull();
        }

        @Test
        void releaseClearsEveryHoldColumn() {
            ShowSeat seat = availableSeat();
            seat.hold(UUID.randomUUID(), UUID.randomUUID(), NOW.plus(HOLD), NOW);
            seat.release();

            assertThat(seat.getStatus()).isEqualTo(ShowSeatStatus.AVAILABLE);
            assertThat(seat.getHoldToken()).isNull();
            assertThat(seat.getHeldByUserId()).isNull();
            assertThat(seat.getHoldExpiresAt()).isNull();
        }

        @Test
        void releaseIsIdempotent() {
            ShowSeat seat = availableSeat();
            seat.release();
            seat.release();

            assertThat(seat.getStatus()).isEqualTo(ShowSeatStatus.AVAILABLE);
        }
    }

    /**
     * A caller reaching this has skipped the availability check that should
     * have produced a 409. Failing loudly beats silently overwriting somebody
     * else's hold.
     */
    @Test
    void holdingAnUnavailableSeatFailsLoudly() {
        ShowSeat seat = availableSeat();
        seat.hold(UUID.randomUUID(), UUID.randomUUID(), NOW.plus(HOLD), NOW);

        assertThatThrownBy(() -> seat.hold(UUID.randomUUID(), UUID.randomUUID(), NOW.plus(HOLD), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HELD");
    }
}
