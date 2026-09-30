package com.harshil.movieticketbooking.show.service;

import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import com.harshil.movieticketbooking.booking.repository.BookingRepository;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.movie.domain.Movie;
import com.harshil.movieticketbooking.movie.service.MovieService;
import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.seat.repository.SeatRepository;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.domain.ShowStatus;
import com.harshil.movieticketbooking.show.dto.ShowRequest;
import com.harshil.movieticketbooking.show.dto.ShowSummaryResponse;
import com.harshil.movieticketbooking.show.repository.ShowRepository;
import com.harshil.movieticketbooking.show.repository.ShowSeatRepository;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.service.ScreenService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of shows, including materialising their seat inventory.
 * <p>
 * <b>Creating a show creates its inventory.</b> One {@code show_seats} row per
 * active seat on the screen, written in the same transaction as the show
 * itself. Doing it up front rather than lazily on first booking is what makes
 * the booking path a pure {@code SELECT ... FOR UPDATE} over existing rows:
 * there is no insert to race on, and
 * {@code uq_show_seats_show_seat} guarantees the inventory is exactly one row
 * per seat.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowService {

    private static final List<BookingStatus> ACTIVE_BOOKING_STATUSES = List.of(
            BookingStatus.HOLD_CREATED, BookingStatus.PAYMENT_PENDING, BookingStatus.CONFIRMED);

    private final ShowRepository showRepository;
    private final ShowSeatRepository showSeatRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final ScreenService screenService;
    private final MovieService movieService;

    @Transactional
    public ShowSummaryResponse createShow(ShowRequest request) {
        Screen screen = screenService.requireScreenWithVenue(request.screenId());
        Movie movie = movieService.requireMovie(request.movieId());

        Instant endsAt = resolveEndsAt(request, movie);
        validateTimeRange(request.startsAt(), endsAt);
        validateNoOverlap(screen.getId(), request.startsAt(), endsAt, null);

        Show show = showRepository.save(Show.builder()
                .screen(screen)
                .movie(movie)
                .startsAt(request.startsAt())
                .endsAt(endsAt)
                .status(ShowStatus.SCHEDULED)
                .build());

        int seatCount = materializeSeatInventory(show, screen);
        log.info("Scheduled show {} ({} on {}) with {} seat(s) of inventory",
                show.getId(), movie.getTitle(), screen.getName(), seatCount);
        return ShowSummaryResponse.from(show, (long) seatCount);
    }

    /**
     * Moves a show in time.
     * <p>
     * Refused once anyone holds or owns a seat for it. Customers booked a
     * specific time; silently moving it under them would be worse than making
     * the admin cancel and re-schedule deliberately.
     */
    @Transactional
    public ShowSummaryResponse rescheduleShow(UUID showId, ShowRequest request) {
        Show show = requireShow(showId);
        if (bookingRepository.existsByShowIdAndStatusIn(showId, ACTIVE_BOOKING_STATUSES)) {
            throw new DomainException(
                    ErrorCode.SHOW_NOT_BOOKABLE,
                    "Show %s has active bookings and cannot be rescheduled".formatted(showId));
        }

        Movie movie = show.getMovie();
        Instant endsAt = resolveEndsAt(request, movie);
        validateTimeRange(request.startsAt(), endsAt);
        validateNoOverlap(show.getScreen().getId(), request.startsAt(), endsAt, showId);

        show.reschedule(request.startsAt(), endsAt);
        return ShowSummaryResponse.from(show);
    }

    /**
     * Cancels a show.
     * <p>
     * Refused while confirmed bookings exist. Cancelling those would mean
     * issuing refunds for every one of them, which is a deliberate
     * money-moving operation, not a side effect of an admin toggling a status.
     * The admin cancels the affected bookings first - each one then goes
     * through the normal refund policy and notification path.
     */
    @Transactional
    public ShowSummaryResponse cancelShow(UUID showId) {
        Show show = requireShow(showId);
        if (bookingRepository.existsByShowIdAndStatusIn(showId, List.of(BookingStatus.CONFIRMED))) {
            throw new DomainException(
                    ErrorCode.SHOW_NOT_BOOKABLE,
                    "Show %s has confirmed bookings; cancel and refund those first".formatted(showId));
        }

        show.changeStatus(ShowStatus.CANCELLED);
        log.info("Show {} cancelled", showId);
        return ShowSummaryResponse.from(show);
    }

    // -----------------------------------------------------------------------

    /** One inventory row per active seat, in the show's own transaction. */
    private int materializeSeatInventory(Show show, Screen screen) {
        List<Seat> seats = seatRepository.findAllByScreenIdAndActiveTrueOrderByRowLabelAscSeatNumberAsc(
                screen.getId());
        if (seats.isEmpty()) {
            throw new DomainException(
                    ErrorCode.SCREEN_HAS_NO_SEATS,
                    "Screen %s has no active seats; create a seat layout first".formatted(screen.getName()));
        }

        List<ShowSeat> inventory = seats.stream()
                .map(seat -> ShowSeat.available(show, seat))
                .toList();
        showSeatRepository.saveAll(inventory);
        return inventory.size();
    }

    private static Instant resolveEndsAt(ShowRequest request, Movie movie) {
        return request.endsAt() != null
                ? request.endsAt()
                : request.startsAt().plusSeconds(movie.getDurationMinutes() * 60L);
    }

    private static void validateTimeRange(Instant startsAt, Instant endsAt) {
        if (!endsAt.isAfter(startsAt)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "endsAt must be after startsAt");
        }
    }

    /** A screen can only play one film at a time. */
    private void validateNoOverlap(UUID screenId, Instant startsAt, Instant endsAt, UUID excludedShowId) {
        if (showRepository.overlapsExistingShow(
                screenId, startsAt, endsAt, ShowStatus.CANCELLED, excludedShowId)) {
            throw new DomainException(
                    ErrorCode.SHOW_OVERLAPS_EXISTING,
                    "Another show already occupies that screen between %s and %s".formatted(startsAt, endsAt));
        }
    }

    private Show requireShow(UUID showId) {
        return showRepository.findByIdWithVenueAndMovie(showId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.SHOW_NOT_FOUND, "Show %s does not exist".formatted(showId)));
    }
}
