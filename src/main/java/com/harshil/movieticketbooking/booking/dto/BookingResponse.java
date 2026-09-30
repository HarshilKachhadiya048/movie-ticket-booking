package com.harshil.movieticketbooking.booking.dto;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.domain.BookingSeat;
import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import com.harshil.movieticketbooking.show.dto.ShowSummaryResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A booking as the customer sees it.
 * <p>
 * Built inside the transaction that loaded the booking, so lazy associations
 * are resolved while a session is still open and no entity ever escapes to the
 * web layer.
 * <p>
 * {@code holdToken} is not exposed. It is an internal correctness device used
 * to re-verify the seat rows at confirmation time; the booking id is what the
 * client needs and the only thing it is authorised by.
 */
public record BookingResponse(
        UUID bookingId,
        String bookingReference,
        BookingStatus status,
        ShowSummaryResponse show,
        List<BookingSeatResponse> seats,
        int seatCount,
        BigDecimal subtotalAmount,
        String discountCode,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        String currency,
        Instant holdExpiresAt,
        Instant confirmedAt,
        Instant cancelledAt,
        Instant createdAt) {

    public static BookingResponse from(Booking booking) {
        return from(booking, booking.getSeats());
    }

    /**
     * Variant for the history listing, where the seats of a whole page are
     * loaded in one query rather than per booking.
     */
    public static BookingResponse from(Booking booking, List<BookingSeat> seats) {
        List<BookingSeatResponse> seatResponses = seats.stream()
                .sorted(Comparator.comparing(BookingSeat::getRowLabel).thenComparing(BookingSeat::getSeatNumber))
                .map(BookingSeatResponse::from)
                .toList();

        return new BookingResponse(
                booking.getId(),
                booking.getBookingReference(),
                booking.getStatus(),
                ShowSummaryResponse.from(booking.getShow()),
                seatResponses,
                booking.getSeatCount(),
                booking.getSubtotalAmount(),
                booking.getDiscountCode() == null ? null : booking.getDiscountCode().getCode(),
                booking.getDiscountAmount(),
                booking.getTotalAmount(),
                booking.getCurrency(),
                booking.getHoldExpiresAt(),
                booking.getConfirmedAt(),
                booking.getCancelledAt(),
                booking.getCreatedAt());
    }
}
