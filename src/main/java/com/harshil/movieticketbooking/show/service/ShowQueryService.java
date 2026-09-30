package com.harshil.movieticketbooking.show.service;

import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.pricing.service.PriceQuote;
import com.harshil.movieticketbooking.pricing.service.PricingService;
import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import com.harshil.movieticketbooking.show.domain.ShowStatus;
import com.harshil.movieticketbooking.show.dto.SeatAvailabilityResponse;
import com.harshil.movieticketbooking.show.dto.ShowSeatMapResponse;
import com.harshil.movieticketbooking.show.dto.ShowSummaryResponse;
import com.harshil.movieticketbooking.show.repository.ShowRepository;
import com.harshil.movieticketbooking.show.repository.ShowSeatRepository;
import com.harshil.movieticketbooking.theater.service.TheaterService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer-facing reads over shows and their seat availability.
 * <p>
 * Availability is always the <em>effective</em> availability - a seat whose
 * hold has lapsed counts as free, exactly as the booking transaction would
 * decide under the row lock. Reporting the raw status would tell customers a
 * seat was unavailable when they could in fact book it.
 */
@Service
@RequiredArgsConstructor
public class ShowQueryService {

    /** How far ahead a theater's schedule is listed when no window is given. */
    private static final Duration DEFAULT_SCHEDULE_WINDOW = Duration.ofDays(7);

    private final ShowRepository showRepository;
    private final ShowSeatRepository showSeatRepository;
    private final PricingService pricingService;
    private final TheaterService theaterService;
    private final Clock clock;

    /**
     * A theater's upcoming schedule with seat counts.
     * <p>
     * Availability for the whole page is counted in one grouped query rather
     * than one query per show.
     */
    @Transactional(readOnly = true)
    public List<ShowSummaryResponse> getTheaterSchedule(UUID theaterId, Instant from, Instant to) {
        theaterService.requireTheaterWithCity(theaterId);

        Instant now = Instant.now(clock);
        Instant windowStart = from == null ? now : from;
        Instant windowEnd = to == null ? windowStart.plus(DEFAULT_SCHEDULE_WINDOW) : to;
        if (!windowEnd.isAfter(windowStart)) {
            throw new DomainException(ErrorCode.INVALID_REQUEST, "'to' must be after 'from'");
        }

        List<Show> shows = showRepository.findScheduleForTheater(
                theaterId, windowStart, windowEnd, ShowStatus.SCHEDULED);
        if (shows.isEmpty()) {
            return List.of();
        }

        Map<UUID, Long> availability = availabilityFor(shows.stream().map(Show::getId).toList(), now);
        return shows.stream()
                .map(show -> ShowSummaryResponse.from(show, availability.getOrDefault(show.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ShowSummaryResponse getShow(UUID showId) {
        Show show = requireShow(showId);
        long available = showSeatRepository.countAvailable(
                showId, Instant.now(clock), ShowSeatStatus.AVAILABLE, ShowSeatStatus.HELD);
        return ShowSummaryResponse.from(show, available);
    }

    /**
     * The full seat map, each seat carrying its effective status and its
     * price, so a customer can see cost and availability before selecting.
     * <p>
     * A snapshot, not a reservation: another customer may take a seat between
     * this response and the hold request. That is inherent to seat selection,
     * and it is precisely why the hold transaction re-checks under a lock
     * rather than trusting what the client had on screen.
     */
    @Transactional(readOnly = true)
    public ShowSeatMapResponse getSeatMap(UUID showId) {
        Show show = requireShow(showId);
        Instant now = Instant.now(clock);

        List<ShowSeat> inventory = showSeatRepository.findSeatMapByShowId(showId);
        List<Seat> seats = inventory.stream().map(ShowSeat::getSeat).toList();

        // One pricing query covers every seat on the map.
        PriceQuote quote = pricingService.quote(show, seats);
        Map<UUID, BigDecimal> priceBySeatId = quote.seats()
                .stream()
                .collect(Collectors.toMap(priced -> priced.seat().getId(), PriceQuote.PricedSeat::price));

        List<SeatAvailabilityResponse> seatResponses = inventory.stream()
                .map(showSeat -> toSeatAvailability(showSeat, priceBySeatId, now))
                .toList();
        long available = seatResponses.stream()
                .filter(seat -> seat.status() == ShowSeatStatus.AVAILABLE)
                .count();

        return new ShowSeatMapResponse(
                ShowSummaryResponse.from(show, available),
                quote.dayType(),
                seatResponses.size(),
                available,
                seatResponses);
    }

    // -----------------------------------------------------------------------

    private Map<UUID, Long> availabilityFor(List<UUID> showIds, Instant now) {
        return showSeatRepository
                .countAvailableByShowIds(showIds, now, ShowSeatStatus.AVAILABLE, ShowSeatStatus.HELD)
                .stream()
                .collect(Collectors.toMap(
                        ShowSeatRepository.ShowAvailabilityProjection::getShowId,
                        ShowSeatRepository.ShowAvailabilityProjection::getAvailableSeats));
    }

    private static SeatAvailabilityResponse toSeatAvailability(
            ShowSeat showSeat,
            Map<UUID, BigDecimal> priceBySeatId,
            Instant now) {
        Seat seat = showSeat.getSeat();
        return new SeatAvailabilityResponse(
                seat.getId(),
                seat.label(),
                seat.getRowLabel(),
                seat.getSeatNumber(),
                seat.getCategory(),
                showSeat.effectiveStatus(now),
                priceBySeatId.get(seat.getId()));
    }

    private Show requireShow(UUID showId) {
        return showRepository.findByIdWithVenueAndMovie(showId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.SHOW_NOT_FOUND, "Show %s does not exist".formatted(showId)));
    }
}
