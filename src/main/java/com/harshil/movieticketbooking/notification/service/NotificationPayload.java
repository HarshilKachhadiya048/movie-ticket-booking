package com.harshil.movieticketbooking.notification.service;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.domain.BookingSeat;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.theater.domain.Screen;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The body of a notification, serialised to JSON and stored on the row.
 * <p>
 * Everything the message needs is copied in here while the business
 * transaction is still open. Delivery happens later, on another thread, with
 * no session attached - so the payload has to be self-contained rather than a
 * set of references that would have to be re-resolved (or would fail to
 * resolve) at send time.
 * <p>
 * Null fields are omitted on serialisation, so a confirmation carries no
 * refund amount and a reminder carries no total.
 */
public record NotificationPayload(
        String bookingReference,
        String movieTitle,
        String theaterName,
        String screenName,
        String cityName,
        Instant showStartsAt,
        List<String> seats,
        BigDecimal totalAmount,
        BigDecimal refundAmount,
        String currency,
        String message) {

    /** Snapshots a booking. Must be called with the session still open. */
    public static NotificationPayload forBooking(Booking booking, String message) {
        return build(booking, null, message);
    }

    /** Snapshots a booking plus the amount returned to the customer. */
    public static NotificationPayload forRefund(Booking booking, BigDecimal refundAmount, String message) {
        return build(booking, refundAmount, message);
    }

    private static NotificationPayload build(Booking booking, BigDecimal refundAmount, String message) {
        Show show = booking.getShow();
        Screen screen = show.getScreen();

        List<String> seatLabels = booking.getSeats()
                .stream()
                .map(BookingSeat::label)
                .toList();

        return new NotificationPayload(
                booking.getBookingReference(),
                show.getMovie().getTitle(),
                screen.getTheater().getName(),
                screen.getName(),
                screen.getTheater().getCity().getName(),
                show.getStartsAt(),
                seatLabels,
                booking.getTotalAmount(),
                refundAmount,
                booking.getCurrency(),
                message);
    }
}
