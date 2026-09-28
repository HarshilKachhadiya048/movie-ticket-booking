package com.harshil.movieticketbooking.booking.dto;

import com.harshil.movieticketbooking.booking.domain.BookingSeat;
import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One booked seat and what it cost.
 * <p>
 * Read from the booking's own price snapshot, never recomputed, so the
 * customer always sees the figure they were actually charged.
 */
public record BookingSeatResponse(
        UUID seatId,
        String label,
        String rowLabel,
        int seatNumber,
        SeatCategory category,
        DayType dayType,
        BigDecimal price) {

    public static BookingSeatResponse from(BookingSeat bookingSeat) {
        return new BookingSeatResponse(
                bookingSeat.getSeat().getId(),
                bookingSeat.label(),
                bookingSeat.getRowLabel(),
                bookingSeat.getSeatNumber(),
                bookingSeat.getSeatCategory(),
                bookingSeat.getDayType(),
                bookingSeat.getPrice());
    }
}
