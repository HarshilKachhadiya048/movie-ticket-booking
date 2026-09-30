package com.harshil.movieticketbooking.booking.dto;

import com.harshil.movieticketbooking.refund.dto.RefundResponse;

/**
 * The result of cancelling a booking.
 *
 * @param booking the booking in its new terminal state
 * @param refund  the refund assessment, or null when the booking was never
 *                paid for. A zero-value refund is still returned, so the
 *                customer can see that a policy was applied and what it
 *                decided, rather than being left to infer it from silence.
 */
public record CancellationResponse(BookingResponse booking, RefundResponse refund) {
}
