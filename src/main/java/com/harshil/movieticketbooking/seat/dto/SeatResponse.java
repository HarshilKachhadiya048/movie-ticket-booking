package com.harshil.movieticketbooking.seat.dto;

import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import java.util.UUID;

public record SeatResponse(
        UUID seatId,
        String label,
        String rowLabel,
        int seatNumber,
        SeatCategory category,
        boolean active) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.label(),
                seat.getRowLabel(),
                seat.getSeatNumber(),
                seat.getCategory(),
                seat.isActive());
    }
}
