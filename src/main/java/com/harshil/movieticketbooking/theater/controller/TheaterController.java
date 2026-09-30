package com.harshil.movieticketbooking.theater.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.security.SecurityExpressions;
import com.harshil.movieticketbooking.show.dto.ShowSummaryResponse;
import com.harshil.movieticketbooking.show.service.ShowQueryService;
import com.harshil.movieticketbooking.theater.dto.TheaterResponse;
import com.harshil.movieticketbooking.theater.service.TheaterService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TheaterController {

    private final TheaterService theaterService;
    private final ShowQueryService showQueryService;

    @GetMapping(ApiEndpoints.Theaters.BY_ID)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public TheaterResponse getTheater(@PathVariable UUID theaterId) {
        return theaterService.getTheater(theaterId);
    }

    /**
     * A theater's schedule, with live seat availability per show.
     *
     * @param from inclusive, defaults to now
     * @param to   exclusive, defaults to seven days after {@code from}
     */
    @GetMapping(ApiEndpoints.Theaters.SHOWS)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public List<ShowSummaryResponse> listShows(
            @PathVariable UUID theaterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return showQueryService.getTheaterSchedule(theaterId, from, to);
    }
}
