package com.harshil.movieticketbooking.booking.controller;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.CancellationResponse;
import com.harshil.movieticketbooking.booking.dto.PayBookingRequest;
import com.harshil.movieticketbooking.booking.service.BookingCancellationService;
import com.harshil.movieticketbooking.booking.service.BookingPaymentService;
import com.harshil.movieticketbooking.booking.service.BookingQueryService;
import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.common.api.PageResponse;
import com.harshil.movieticketbooking.security.AppUserDetails;
import com.harshil.movieticketbooking.security.SecurityExpressions;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paying for, cancelling and reading bookings.
 * <p>
 * {@code hasRole('CUSTOMER')} is the coarse check. Whether a booking is
 * <em>yours</em> cannot be decided from the URL, so ownership is enforced in
 * the service layer against the loaded row, which also lets it be reported as
 * {@code UNAUTHORIZED_BOOKING_ACCESS} rather than a bare 403.
 */
@RestController
@RequiredArgsConstructor
public class BookingController {

    private final BookingQueryService bookingQueryService;
    private final BookingPaymentService bookingPaymentService;
    private final BookingCancellationService bookingCancellationService;

    /** The caller's own booking history, newest first. */
    @GetMapping(ApiEndpoints.Bookings.ROOT)
    @PreAuthorize(SecurityExpressions.IS_CUSTOMER)
    public PageResponse<BookingResponse> listBookings(
            @AuthenticationPrincipal AppUserDetails principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return bookingQueryService.getHistory(principal.userId(), pageable);
    }

    @GetMapping(ApiEndpoints.Bookings.BY_ID)
    @PreAuthorize(SecurityExpressions.IS_CUSTOMER)
    public BookingResponse getBooking(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingQueryService.getBooking(bookingId, principal.userId());
    }

    /**
     * Pays for a held booking and confirms it.
     * <p>
     * Idempotent on {@code idempotencyKey}: a retry of the same request
     * replays the original outcome rather than charging twice. A decline
     * returns <b>402</b>; a hold that lapsed before the charge settled returns
     * <b>409</b> (and the charge, if any, is refunded).
     */
    @PostMapping(ApiEndpoints.Bookings.PAYMENT)
    @PreAuthorize(SecurityExpressions.IS_CUSTOMER)
    public BookingResponse payForBooking(
            @PathVariable UUID bookingId,
            @Valid @RequestBody PayBookingRequest request,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingPaymentService.pay(bookingId, principal.userId(), request);
    }

    /**
     * Cancels a booking and applies the refund policy.
     * <p>
     * Idempotent: cancelling an already-cancelled booking returns the original
     * outcome instead of refunding a second time.
     */
    @PostMapping(ApiEndpoints.Bookings.CANCEL)
    @PreAuthorize(SecurityExpressions.IS_CUSTOMER)
    public CancellationResponse cancelBooking(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AppUserDetails principal) {
        return bookingCancellationService.cancel(bookingId, principal.userId());
    }
}
