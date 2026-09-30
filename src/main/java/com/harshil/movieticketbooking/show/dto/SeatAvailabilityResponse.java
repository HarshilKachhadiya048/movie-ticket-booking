package com.harshil.movieticketbooking.show.dto;

import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One seat on a show's seat map.
 * <p>
 * {@code status} is the <em>effective</em> status: a seat whose hold has
 * lapsed reads AVAILABLE here, matching exactly what the booking path would
 * decide under the row lock. Showing the raw column would tell customers a
 * seat was taken when they could in fact book it.
 * <p>
 * It is still a snapshot, not a reservation - another customer may take the
 * seat between this response and a hold request. That is inherent to seat
 * selection, and it is why the hold transaction re-checks under a lock rather
 * than trusting what the client saw.
 * <p>
 * {@code price} is the resolved price for this seat on this show, so the
 * customer sees the cost before selecting rather than after.
 */
public record SeatAvailabilityResponse(
        UUID seatId,
        String label,
        String rowLabel,
        int seatNumber,
        SeatCategory category,
        ShowSeatStatus status,
        BigDecimal price) {
}
