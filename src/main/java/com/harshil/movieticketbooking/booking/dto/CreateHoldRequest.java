package com.harshil.movieticketbooking.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request to hold seats for a show.
 * <p>
 * Bean validation only covers what can be judged from the request alone -
 * shape, presence, bounds. Whether the seats exist, belong to this show and
 * are free is a question about shared state, so it is answered under the row
 * lock in
 * {@link com.harshil.movieticketbooking.booking.service.SeatHoldService} and
 * reported as 409, not 400.
 *
 * @param seatIds      physical seat ids; the upper bound here is a cheap guard
 *                     so an absurd request is rejected before it reaches the
 *                     database. The configured
 *                     {@code booking.max-seats-per-booking} is the real limit.
 * @param discountCode optional; applied at hold time so the customer sees the
 *                     final payable amount before paying
 */
public record CreateHoldRequest(
        @NotNull(message = "showId is required") UUID showId,
        @NotEmpty(message = "At least one seat must be selected")
        @Size(max = 50, message = "Too many seats requested")
        List<@NotNull UUID> seatIds,
        @Size(max = 40, message = "Discount code must not exceed 40 characters") String discountCode) {
}
