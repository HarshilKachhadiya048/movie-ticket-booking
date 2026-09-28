package com.harshil.movieticketbooking.theater.service;

import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.seat.dto.SeatLayoutRequest;
import com.harshil.movieticketbooking.seat.dto.SeatResponse;
import com.harshil.movieticketbooking.seat.repository.SeatRepository;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.Theater;
import com.harshil.movieticketbooking.theater.dto.ScreenRequest;
import com.harshil.movieticketbooking.theater.dto.ScreenResponse;
import com.harshil.movieticketbooking.theater.repository.ScreenRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Screens and their physical seat layouts. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenService {

    private final ScreenRepository screenRepository;
    private final SeatRepository seatRepository;
    private final TheaterService theaterService;

    @Transactional(readOnly = true)
    public List<ScreenResponse> listScreens(UUID theaterId) {
        theaterService.requireTheaterWithCity(theaterId);
        return screenRepository.findAllByTheaterIdOrderByNameAsc(theaterId)
                .stream()
                .map(screen -> ScreenResponse.from(screen, seatRepository.countByScreenId(screen.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ScreenResponse getScreen(UUID screenId) {
        Screen screen = requireScreen(screenId);
        return ScreenResponse.from(screen, seatRepository.countByScreenId(screenId));
    }

    @Transactional
    public ScreenResponse createScreen(ScreenRequest request) {
        Theater theater = theaterService.requireTheaterWithCity(request.theaterId());
        if (screenRepository.existsByTheaterIdAndNameIgnoreCase(theater.getId(), request.name())) {
            throw new DomainException(
                    ErrorCode.SCREEN_ALREADY_EXISTS,
                    "'%s' already exists in %s".formatted(request.name(), theater.getName()));
        }

        Screen screen = screenRepository.save(Screen.builder()
                .theater(theater)
                .name(request.name())
                .screenType(request.screenType())
                .active(request.activeOrDefault())
                .build());
        return ScreenResponse.from(screen, 0L);
    }

    @Transactional
    public ScreenResponse updateScreen(UUID screenId, ScreenRequest request) {
        Screen screen = requireScreen(screenId);
        if (screenRepository.existsByTheaterIdAndNameIgnoreCaseAndIdNot(
                screen.getTheater().getId(), request.name(), screenId)) {
            throw new DomainException(
                    ErrorCode.SCREEN_ALREADY_EXISTS,
                    "'%s' already exists in that theater".formatted(request.name()));
        }

        screen.update(request.name(), request.screenType(), request.activeOrDefault());
        return ScreenResponse.from(screen, seatRepository.countByScreenId(screenId));
    }

    @Transactional
    public void deactivateScreen(UUID screenId) {
        Screen screen = requireScreen(screenId);
        screen.update(screen.getName(), screen.getScreenType(), false);
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> listSeats(UUID screenId) {
        requireScreen(screenId);
        return seatRepository.findAllByScreenIdOrderByRowLabelAscSeatNumberAsc(screenId)
                .stream()
                .map(SeatResponse::from)
                .toList();
    }

    /**
     * Expands a row description into individual seats.
     * <p>
     * <b>Refuses to run twice.</b> Every show on this screen has already
     * materialised its own {@code show_seats} inventory from this layout;
     * adding or removing seats afterwards would leave existing shows with
     * inventory that no longer matches the auditorium, and could orphan seats
     * customers have already booked. Changing a live layout is a data
     * migration, not an API call, so the second attempt is rejected rather
     * than silently half-applied.
     */
    @Transactional
    public List<SeatResponse> createSeatLayout(UUID screenId, SeatLayoutRequest request) {
        Screen screen = requireScreen(screenId);
        if (seatRepository.countByScreenId(screenId) > 0) {
            throw new DomainException(
                    ErrorCode.SEAT_LAYOUT_NOT_EMPTY,
                    "Screen %s already has a seat layout".formatted(screen.getName()));
        }
        validateRowsAreUnique(request);

        List<Seat> seats = new ArrayList<>();
        for (SeatLayoutRequest.SeatRowRequest row : request.rows()) {
            for (int seatNumber = 1; seatNumber <= row.seatCount(); seatNumber++) {
                seats.add(Seat.builder()
                        .screen(screen)
                        .rowLabel(row.rowLabel())
                        .seatNumber(seatNumber)
                        .category(row.category())
                        .active(true)
                        .build());
            }
        }

        List<Seat> saved = seatRepository.saveAll(seats);
        log.info("Created {} seat(s) across {} row(s) on screen {}",
                saved.size(), request.rows().size(), screen.getName());
        return saved.stream().map(SeatResponse::from).toList();
    }

    /** Shared with {@code ShowService} and {@code PricingRuleService}. */
    @Transactional(readOnly = true)
    public Screen requireScreenWithVenue(UUID screenId) {
        return screenRepository.findByIdWithTheaterAndCity(screenId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.SCREEN_NOT_FOUND, "Screen %s does not exist".formatted(screenId)));
    }

    private Screen requireScreen(UUID screenId) {
        return screenRepository.findByIdWithTheaterAndCity(screenId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.SCREEN_NOT_FOUND, "Screen %s does not exist".formatted(screenId)));
    }

    private static void validateRowsAreUnique(SeatLayoutRequest request) {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = request.rows()
                .stream()
                .map(SeatLayoutRequest.SeatRowRequest::rowLabel)
                .filter(rowLabel -> !seen.add(rowLabel))
                .distinct()
                .toList();
        if (!duplicates.isEmpty()) {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST,
                    "Duplicate row label(s): %s".formatted(String.join(", ", duplicates)));
        }
    }
}
