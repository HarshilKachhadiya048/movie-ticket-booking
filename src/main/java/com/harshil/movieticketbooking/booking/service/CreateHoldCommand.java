package com.harshil.movieticketbooking.booking.service;

import java.util.List;
import java.util.UUID;

/**
 * What the seat hold transaction needs, decoupled from the HTTP request shape.
 *
 * @param showId       the show being booked
 * @param seatIds      physical seat ids; order is irrelevant, the service
 *                     sorts them before locking
 * @param discountCode optional promotional code, validated at hold time so the
 *                     customer sees the final payable amount before paying
 * @param userId       the authenticated customer
 */
public record CreateHoldCommand(
        UUID showId,
        List<UUID> seatIds,
        String discountCode,
        UUID userId) {
}
