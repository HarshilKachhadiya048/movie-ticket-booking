package com.harshil.movieticketbooking.show.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.security.SecurityExpressions;
import com.harshil.movieticketbooking.show.dto.ShowSeatMapResponse;
import com.harshil.movieticketbooking.show.dto.ShowSummaryResponse;
import com.harshil.movieticketbooking.show.service.ShowQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ShowController {

    private final ShowQueryService showQueryService;

    @GetMapping(ApiEndpoints.Shows.BY_ID)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public ShowSummaryResponse getShow(@PathVariable UUID showId) {
        return showQueryService.getShow(showId);
    }

    /**
     * The seat map, each seat carrying its effective availability and price.
     * <p>
     * A snapshot rather than a reservation - seats can be taken between this
     * call and a hold request, which is why the hold transaction re-checks
     * under a row lock.
     */
    @GetMapping(ApiEndpoints.Shows.SEATS)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public ShowSeatMapResponse getSeatMap(@PathVariable UUID showId) {
        return showQueryService.getSeatMap(showId);
    }
}
