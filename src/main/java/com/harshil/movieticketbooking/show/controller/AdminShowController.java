package com.harshil.movieticketbooking.show.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.show.dto.ShowRequest;
import com.harshil.movieticketbooking.show.dto.ShowSummaryResponse;
import com.harshil.movieticketbooking.show.service.ShowService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Show administration.
 * <p>
 * Creating a show also materialises one {@code show_seats} row per active seat
 * on the screen, in the same transaction, so the show is either fully bookable
 * or does not exist.
 */
@RestController
@RequiredArgsConstructor
public class AdminShowController {

    private final ShowService showService;

    @PostMapping(ApiEndpoints.Admin.Shows.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public ShowSummaryResponse createShow(@Valid @RequestBody ShowRequest request) {
        return showService.createShow(request);
    }

    /** Refused with <b>409</b> while the show has active bookings. */
    @PutMapping(ApiEndpoints.Admin.Shows.BY_ID)
    public ShowSummaryResponse rescheduleShow(
            @PathVariable UUID showId,
            @Valid @RequestBody ShowRequest request) {
        return showService.rescheduleShow(showId, request);
    }

    /**
     * Cancels a show. Refused with <b>409</b> while confirmed bookings exist -
     * those have to be cancelled individually so each one goes through the
     * refund policy rather than being voided silently.
     */
    @DeleteMapping(ApiEndpoints.Admin.Shows.BY_ID)
    public ShowSummaryResponse cancelShow(@PathVariable UUID showId) {
        return showService.cancelShow(showId);
    }
}
