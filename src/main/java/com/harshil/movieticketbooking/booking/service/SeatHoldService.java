package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.config.BookingProperties;
import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.domain.BookingSeat;
import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.repository.BookingRepository;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.discount.service.DiscountApplication;
import com.harshil.movieticketbooking.discount.service.DiscountService;
import com.harshil.movieticketbooking.pricing.service.PriceQuote;
import com.harshil.movieticketbooking.pricing.service.PricingService;
import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.seat.repository.SeatRepository;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import com.harshil.movieticketbooking.show.repository.ShowRepository;
import com.harshil.movieticketbooking.show.repository.ShowSeatRepository;
import com.harshil.movieticketbooking.user.domain.User;
import com.harshil.movieticketbooking.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a time-bound hold on seats. This is the correctness-critical path of
 * the whole system.
 *
 * <h2>The guarantee</h2>
 * Two customers can never both be told they have the same seat for the same
 * show. That is enforced by PostgreSQL row locks, not by application
 * coordination:
 * <ol>
 *     <li>{@link ShowSeatRepository#lockByShowAndSeatIds} issues
 *     {@code SELECT ... FOR UPDATE} on exactly the requested inventory rows.
 *     The second transaction to ask for a contended row <em>blocks inside the
 *     database</em> until the first commits or rolls back.</li>
 *     <li>Validation and mutation both happen after that lock and before the
 *     commit that releases it, so the state a request checks is the state it
 *     changes. There is no window between them.</li>
 *     <li>The loser therefore does not see stale data and "get lucky" - it
 *     resumes, re-reads the now-committed HELD row and is rejected with 409.</li>
 * </ol>
 * No {@code synchronized}, no {@code ReentrantLock}, no distributed lock. Each
 * of those would only coordinate one JVM, or add a second source of truth that
 * can disagree with the database. The lock lives where the data lives, so the
 * guarantee survives multiple instances, connection pools and restarts.
 * {@code uq_show_seats_show_seat} backs it structurally: duplicate inventory
 * for a seat cannot even be represented.
 *
 * <h2>Lock ordering</h2>
 * Seat ids are sorted before locking and the query orders by {@code seat_id},
 * so every transaction walks contended rows in the same sequence. Two
 * overlapping multi-seat requests then queue behind each other instead of each
 * holding what the other needs. The discount code, if any, is always locked
 * <em>after</em> the seats - again a fixed global order.
 *
 * <h2>Lazy expiry</h2>
 * A row still marked HELD whose {@code hold_expires_at} has passed counts as
 * available, evaluated here under the lock. An expired hold is therefore
 * re-bookable the instant it lapses. The cleanup scheduler only tidies rows
 * up; if it is late, stopped, or has never run, seats are still correctly
 * allocated.
 *
 * <h2>Transaction scope</h2>
 * This transaction touches the database only. No gateway call, no
 * notification, no HTTP happens while these row locks are held - the payment
 * call comes later, in
 * {@link BookingPaymentService}, between two separate transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatHoldService {

    private final ShowRepository showRepository;
    private final ShowSeatRepository showSeatRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PricingService pricingService;
    private final DiscountService discountService;
    private final BookingReferenceGenerator referenceGenerator;
    private final BookingProperties bookingProperties;
    private final Clock clock;

    /**
     * Holds the requested seats and creates the booking that owns them.
     * <p>
     * Returns a DTO rather than the entity: the response is assembled while
     * the session is still open, so no lazy association can be touched after
     * the transaction ends.
     *
     * @return the new booking in {@link BookingStatus#HOLD_CREATED}, with its
     *         priced seats and payable total
     * @throws DomainException 409 if any seat is booked, actively held, or not
     *                         part of this show; 422 if a discount code is
     *                         unusable; 404 if the show or a seat is unknown
     */
    @Transactional
    public BookingResponse createHold(CreateHoldCommand command) {
        List<UUID> seatIds = normalizeSeatIds(command.seatIds());
        Instant now = Instant.now(clock);

        Show show = showRepository.findByIdWithVenueAndMovie(command.showId())
                .orElseThrow(() -> new DomainException(
                        ErrorCode.SHOW_NOT_FOUND, "Show %s does not exist".formatted(command.showId())));
        validateShowIsBookable(show, now);

        // Seats are immutable reference data; reading them needs no lock, and
        // having them in hand lets the conflict report name seats rather than ids.
        Map<UUID, Seat> seatsById = loadSeats(seatIds, show);

        // ---- The locked section. Everything below runs under FOR UPDATE. ----
        List<ShowSeat> lockedSeats = showSeatRepository.lockByShowAndSeatIds(show.getId(), seatIds);
        validateAllSeatsBelongToShow(lockedSeats, seatIds, show);
        validateAllSeatsAvailable(lockedSeats, seatsById, now, command.userId());

        PriceQuote quote = pricingService.quote(show, orderedSeats(lockedSeats, seatsById));
        Optional<DiscountApplication> discount = discountService.evaluate(
                command.discountCode(), command.userId(), quote.subtotal(), now);

        Booking booking = persistBooking(command, show, quote, discount, now);
        discount.ifPresent(application ->
                discountService.recordRedemption(application, command.userId(), booking.getId()));

        applyHold(lockedSeats, booking, seatsById, quote, now);
        // ---- Locks are released when this transaction commits. ----

        log.info("Booking {} holds {} seat(s) on show {} until {}",
                booking.getBookingReference(), seatIds.size(), show.getId(), booking.getHoldExpiresAt());
        return BookingResponse.from(booking);
    }

    // -----------------------------------------------------------------------
    // Request validation
    // -----------------------------------------------------------------------

    /**
     * Deduplicates, bounds and sorts the requested seats.
     * <p>
     * Sorting is not cosmetic - it is what gives every transaction the same
     * lock acquisition order and keeps concurrent multi-seat requests from
     * deadlocking.
     */
    private List<UUID> normalizeSeatIds(List<UUID> requestedSeatIds) {
        if (requestedSeatIds == null || requestedSeatIds.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "At least one seat must be requested");
        }
        Set<UUID> unique = new LinkedHashSet<>(requestedSeatIds);
        if (unique.size() != requestedSeatIds.size()) {
            throw new DomainException(ErrorCode.DUPLICATE_SEAT_IN_REQUEST);
        }
        if (unique.size() > bookingProperties.maxSeatsPerBooking()) {
            throw new DomainException(
                    ErrorCode.TOO_MANY_SEATS,
                    "At most %d seats may be booked at once".formatted(bookingProperties.maxSeatsPerBooking()));
        }
        return unique.stream().sorted().toList();
    }

    private void validateShowIsBookable(Show show, Instant now) {
        if (!show.getStatus().isOpenForBooking()) {
            throw new DomainException(
                    ErrorCode.SHOW_NOT_BOOKABLE, "Show %s is %s".formatted(show.getId(), show.getStatus()));
        }
        if (show.hasStarted(now)) {
            throw new DomainException(
                    ErrorCode.SHOW_ALREADY_STARTED,
                    "Show %s started at %s".formatted(show.getId(), show.getStartsAt()));
        }
    }

    private Map<UUID, Seat> loadSeats(List<UUID> seatIds, Show show) {
        Map<UUID, Seat> seatsById = seatRepository.findAllByIdIn(seatIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(Seat::getId, Function.identity()));

        List<UUID> missing = seatIds.stream()
                .filter(seatId -> !seatsById.containsKey(seatId))
                .toList();
        if (!missing.isEmpty()) {
            throw new DomainException(ErrorCode.SEAT_NOT_FOUND, "One or more seats do not exist")
                    .withDetail("seatIds", missing);
        }

        // Cheap pre-check: a seat from another screen can never have inventory
        // for this show, and catching it here gives a clearer error than an
        // unexplained short result from the locking query.
        UUID screenId = show.getScreen().getId();
        List<UUID> foreign = seatsById.values()
                .stream()
                .filter(seat -> !seat.getScreen().getId().equals(screenId))
                .map(Seat::getId)
                .toList();
        if (!foreign.isEmpty()) {
            throw new DomainException(ErrorCode.SEAT_NOT_IN_SHOW)
                    .withDetail("seatIds", foreign);
        }
        return seatsById;
    }

    // -----------------------------------------------------------------------
    // Validation under the row lock
    // -----------------------------------------------------------------------

    private void validateAllSeatsBelongToShow(List<ShowSeat> lockedSeats, List<UUID> seatIds, Show show) {
        if (lockedSeats.size() == seatIds.size()) {
            return;
        }
        Set<UUID> found = new HashSet<>();
        lockedSeats.forEach(showSeat -> found.add(showSeat.getSeat().getId()));

        List<UUID> missing = seatIds.stream()
                .filter(seatId -> !found.contains(seatId))
                .toList();
        throw new DomainException(
                ErrorCode.SEAT_NOT_IN_SHOW,
                "Show %s has no inventory for %d of the requested seats".formatted(show.getId(), missing.size()))
                .withDetail("seatIds", missing);
    }

    /**
     * Rejects the whole request if any seat is unavailable - a partial hold
     * would leave a customer paying for a fragment of the seats they asked for.
     * <p>
     * A seat already BOOKED is reported ahead of one merely HELD: booked is
     * permanent, held may free up, and that difference is worth telling the
     * customer.
     */
    private void validateAllSeatsAvailable(
            List<ShowSeat> lockedSeats,
            Map<UUID, Seat> seatsById,
            Instant now,
            UUID requestingUserId) {
        List<String> booked = new ArrayList<>();
        List<String> held = new ArrayList<>();
        boolean heldByRequester = false;

        for (ShowSeat showSeat : lockedSeats) {
            ShowSeatStatus effective = showSeat.effectiveStatus(now);
            if (effective == ShowSeatStatus.AVAILABLE) {
                continue;
            }
            String label = seatsById.get(showSeat.getSeat().getId()).label();
            if (effective == ShowSeatStatus.BOOKED) {
                booked.add(label);
            } else {
                held.add(label);
                heldByRequester |= requestingUserId.equals(showSeat.getHeldByUserId());
            }
        }

        if (!booked.isEmpty()) {
            throw new DomainException(
                    ErrorCode.SEAT_ALREADY_BOOKED,
                    "Seat(s) %s are already booked".formatted(String.join(", ", booked)))
                    .withDetail("bookedSeats", booked)
                    .withDetail("heldSeats", held);
        }
        if (!held.isEmpty()) {
            // Includes holds owned by the requester: releasing their earlier
            // hold implicitly would silently invalidate that other booking.
            String message = heldByRequester
                    ? "Seat(s) %s are already held under another of your bookings".formatted(String.join(", ", held))
                    : "Seat(s) %s are currently held by another customer".formatted(String.join(", ", held));
            throw new DomainException(ErrorCode.SEAT_ALREADY_HELD, message)
                    .withDetail("heldSeats", held);
        }
    }

    // -----------------------------------------------------------------------
    // Mutation under the row lock
    // -----------------------------------------------------------------------

    private List<Seat> orderedSeats(List<ShowSeat> lockedSeats, Map<UUID, Seat> seatsById) {
        return lockedSeats.stream()
                .map(showSeat -> seatsById.get(showSeat.getSeat().getId()))
                .sorted(Comparator.comparing(Seat::getRowLabel).thenComparing(Seat::getSeatNumber))
                .toList();
    }

    private Booking persistBooking(
            CreateHoldCommand command,
            Show show,
            PriceQuote quote,
            Optional<DiscountApplication> discount,
            Instant now) {
        User user = userRepository.getReferenceById(command.userId());

        BigDecimal subtotal = Money.normalize(quote.subtotal());
        BigDecimal discountAmount = discount
                .map(DiscountApplication::discountAmount)
                .map(Money::normalize)
                .orElse(Money.ZERO);
        BigDecimal total = Money.subtract(subtotal, discountAmount);

        Booking booking = Booking.builder()
                .bookingReference(referenceGenerator.generate())
                .user(user)
                .show(show)
                .status(BookingStatus.HOLD_CREATED)
                .holdToken(UUID.randomUUID())
                .holdExpiresAt(now.plus(bookingProperties.holdDuration()))
                .seatCount(quote.seats().size())
                .subtotalAmount(subtotal)
                .discountCode(discount.map(DiscountApplication::discountCode).orElse(null))
                .discountAmount(discountAmount)
                .totalAmount(total)
                .currency(bookingProperties.currency())
                .build();

        // Flush so the booking has an id for the discount usage row and for
        // the booking_seats foreign key.
        return bookingRepository.saveAndFlush(booking);
    }

    private void applyHold(
            List<ShowSeat> lockedSeats,
            Booking booking,
            Map<UUID, Seat> seatsById,
            PriceQuote quote,
            Instant now) {
        Map<UUID, BigDecimal> priceBySeatId = quote.seats()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        priced -> priced.seat().getId(), PriceQuote.PricedSeat::price));

        for (ShowSeat showSeat : lockedSeats) {
            Seat seat = seatsById.get(showSeat.getSeat().getId());
            showSeat.hold(booking.getHoldToken(), booking.getUser().getId(), booking.getHoldExpiresAt(), now);
            booking.addSeat(BookingSeat.snapshot(
                    showSeat, seat, quote.dayType(), priceBySeatId.get(seat.getId())));
        }
    }
}
