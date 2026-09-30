package com.harshil.movieticketbooking.theater.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.seat.dto.SeatLayoutRequest;
import com.harshil.movieticketbooking.seat.dto.SeatResponse;
import com.harshil.movieticketbooking.theater.dto.ScreenRequest;
import com.harshil.movieticketbooking.theater.dto.ScreenResponse;
import com.harshil.movieticketbooking.theater.service.ScreenService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Screen and seat-layout administration.
 * <p>
 * The layout endpoint takes row descriptions - "row A, 12 premium seats" -
 * rather than a list of individual seats, because that is how an auditorium is
 * actually laid out and enumerating two hundred seats over HTTP would be
 * tedious and easy to get wrong.
 */
@RestController
@RequiredArgsConstructor
public class AdminScreenController {

    private final ScreenService screenService;

    @GetMapping(ApiEndpoints.Admin.Screens.ROOT)
    public List<ScreenResponse> listScreens(@RequestParam UUID theaterId) {
        return screenService.listScreens(theaterId);
    }

    @GetMapping(ApiEndpoints.Admin.Screens.BY_ID)
    public ScreenResponse getScreen(@PathVariable UUID screenId) {
        return screenService.getScreen(screenId);
    }

    @PostMapping(ApiEndpoints.Admin.Screens.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenResponse createScreen(@Valid @RequestBody ScreenRequest request) {
        return screenService.createScreen(request);
    }

    @PutMapping(ApiEndpoints.Admin.Screens.BY_ID)
    public ScreenResponse updateScreen(
            @PathVariable UUID screenId,
            @Valid @RequestBody ScreenRequest request) {
        return screenService.updateScreen(screenId, request);
    }

    @DeleteMapping(ApiEndpoints.Admin.Screens.BY_ID)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateScreen(@PathVariable UUID screenId) {
        screenService.deactivateScreen(screenId);
    }

    @GetMapping(ApiEndpoints.Admin.Screens.SEATS)
    public List<SeatResponse> listSeats(@PathVariable UUID screenId) {
        return screenService.listSeats(screenId);
    }

    /**
     * Creates the screen's seat layout. Rejected with <b>422</b> if a layout
     * already exists - existing shows have already materialised inventory from
     * it, so changing it is a data migration rather than an API call.
     */
    @PostMapping(ApiEndpoints.Admin.Screens.SEATS)
    @ResponseStatus(HttpStatus.CREATED)
    public List<SeatResponse> createSeatLayout(
            @PathVariable UUID screenId,
            @Valid @RequestBody SeatLayoutRequest request) {
        return screenService.createSeatLayout(screenId, request);
    }
}
