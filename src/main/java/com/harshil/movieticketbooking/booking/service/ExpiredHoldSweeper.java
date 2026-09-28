package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.config.BookingProperties;
import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import com.harshil.movieticketbooking.booking.repository.BookingRepository;
import com.harshil.movieticketbooking.booking.repository.BookingSeatRepository;
import com.harshil.movieticketbooking.discount.service.DiscountService;
import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import com.harshil.movieticketbooking.show.repository.ShowSeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tidies up holds that lapsed without payment.
 *
 * <h2>This is a cleanup, not a correctness mechanism</h2>
 * The brief is explicit that the scheduler must not be the only thing that
 * makes an expired seat bookable, and here it is not the thing that makes it
 * bookable <em>at all</em>. A lapsed hold is already treated as available by
 * {@code ShowSeat.effectiveStatus}, evaluated under the row lock inside the
 * hold transaction. If this sweeper runs late, is switched off, or has never
 * run in this deployment, seats are still allocated correctly - a customer
 * simply takes the stale row over.
 * <p>
 * What the sweep is actually for:
 * <ul>
 *     <li>seat maps and availability counts read naturally, without every
 *     query having to reason about expiry;</li>
 *     <li>abandoned bookings reach the terminal EXPIRED state instead of
 *     sitting in HOLD_CREATED forever;</li>
 *     <li>discount codes reserved by an abandoned hold return to circulation.</li>
 * </ul>
 * That last one is the only user-visible consequence of the sweep not running,
 * and it is bounded: the redemption is also released whenever the booking is
 * cancelled or its payment fails.
 *
 * <h2>Safety</h2>
 * Every release is guarded by the hold token. Once a hold lapses, another
 * customer can legitimately take the seat through lazy expiry; releasing on
 * status alone would take that seat away from them. Candidate rows are read
 * without a lock and re-checked under one, so anything claimed in between is
 * skipped rather than clobbered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiredHoldSweeper {

    private static final List<BookingStatus> HOLDING_STATUSES =
            List.of(BookingStatus.HOLD_CREATED, BookingStatus.PAYMENT_PENDING);

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final ShowSeatRepository showSeatRepository;
    private final DiscountService discountService;
    private final BookingProperties bookingProperties;
    private final Clock clock;

    /**
     * Runs one sweep.
     *
     * @return how many bookings were expired
     */
    @Transactional
    public int sweep() {
        Instant now = Instant.now(clock);
        int expiredBookings = expireLapsedBookings(now);
        int orphanedSeats = releaseOrphanedSeats(now);

        if (expiredBookings > 0 || orphanedSeats > 0) {
            log.info("Hold sweep expired {} booking(s) and released {} orphaned seat row(s)",
                    expiredBookings, orphanedSeats);
        }
        return expiredBookings;
    }

    /** Expires bookings whose hold has lapsed and frees the seats they held. */
    private int expireLapsedBookings(Instant now) {
        Limit limit = Limit.of(bookingProperties.holdSweeper().batchSize());
        List<UUID> candidateIds = bookingRepository.findExpiredHoldBookingIds(now, HOLDING_STATUSES, limit);

        int expired = 0;
        for (UUID bookingId : candidateIds) {
            // Re-read under a lock: the candidate list was read without one,
            // so the booking may have been paid for or cancelled since.
            Booking booking = bookingRepository.lockById(bookingId).orElse(null);
            if (booking == null || !booking.getStatus().holdsSeats() || !booking.isHoldExpired(now)) {
                continue;
            }

            releaseSeatsHeldBy(booking);
            discountService.releaseRedemption(bookingId);
            booking.expire();
            expired++;
        }
        return expired;
    }

    /**
     * Second pass for any HELD row with a lapsed expiry that no active booking
     * still points at.
     * <p>
     * Defensive rather than expected: the first pass should already have
     * covered every such row. It exists so that a row left inconsistent by an
     * earlier bug or a partial failure is eventually reclaimed instead of
     * showing as HELD forever.
     */
    private int releaseOrphanedSeats(Instant now) {
        Limit limit = Limit.of(bookingProperties.holdSweeper().batchSize());
        List<UUID> expiredIds = showSeatRepository.findExpiredHoldIds(now, ShowSeatStatus.HELD, limit);
        if (expiredIds.isEmpty()) {
            return 0;
        }

        List<ShowSeat> lockedSeats = showSeatRepository.lockByIds(expiredIds);
        int released = 0;
        for (ShowSeat showSeat : lockedSeats) {
            if (showSeat.getStatus() == ShowSeatStatus.HELD && showSeat.isHoldExpired(now)) {
                showSeat.release();
                released++;
            }
        }
        return released;
    }

    private void releaseSeatsHeldBy(Booking booking) {
        List<UUID> showSeatIds = bookingSeatRepository.findShowSeatIdsByBookingId(booking.getId());
        if (showSeatIds.isEmpty()) {
            return;
        }
        UUID holdToken = booking.getHoldToken();
        showSeatRepository.lockByIds(showSeatIds)
                .stream()
                .filter(showSeat -> showSeat.isHeldUnder(holdToken))
                .forEach(ShowSeat::release);
    }
}
