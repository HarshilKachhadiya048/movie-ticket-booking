package com.harshil.movieticketbooking.show.dto;

import com.harshil.movieticketbooking.pricing.domain.DayType;
import java.util.List;

/**
 * A show's full seat map with live availability and prices.
 *
 * @param dayType        which pricing tier applies, resolved in the cinema's
 *                       local time zone - returned so a client can explain why
 *                       weekend prices are showing
 * @param totalSeats     every seat on the screen, whatever its state
 * @param availableSeats how many are bookable right now
 */
public record ShowSeatMapResponse(
        ShowSummaryResponse show,
        DayType dayType,
        int totalSeats,
        long availableSeats,
        List<SeatAvailabilityResponse> seats) {
}
