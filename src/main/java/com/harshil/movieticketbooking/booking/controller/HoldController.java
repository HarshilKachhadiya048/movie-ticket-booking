package com.harshil.movieticketbooking.booking.controller;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.CreateHoldRequest;
import com.harshil.movieticketbooking.booking.service.CreateHoldCommand;
import com.harshil.movieticketbooking.booking.service.SeatHoldService;
import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.security.AppUserDetails;
import com.harshil.movieticketbooking.security.SecurityExpressions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Holding seats - the entry point to a booking.
 * <p>
 * Thin by design: the controller binds and validates the request, supplies the
 * authenticated user id, and delegates. All of the interesting behaviour -
 * locking, expiry, pricing, discounting - lives in
 * {@link SeatHoldService}, where it can be tested without HTTP.
 * <p>
 * A successful hold returns <b>201</b> with the booking and its payable total.
 * Contention returns <b>409</b>: the request was valid, somebody else got
 * there first.
 */
@RestController
@RequiredArgsConstructor
public class HoldController {

    private final SeatHoldService seatHoldService;

    @PostMapping(ApiEndpoints.Holds.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(SecurityExpressions.IS_CUSTOMER)
    public BookingResponse createHold(
            @Valid @RequestBody CreateHoldRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return seatHoldService.createHold(new CreateHoldCommand(
                request.showId(),
                request.seatIds(),
                request.discountCode(),
                principal.userId()));
    }
}
