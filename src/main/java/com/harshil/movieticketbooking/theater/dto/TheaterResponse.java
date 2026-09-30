package com.harshil.movieticketbooking.theater.dto;

import com.harshil.movieticketbooking.theater.domain.Theater;
import java.util.UUID;

public record TheaterResponse(
        UUID theaterId,
        UUID cityId,
        String cityName,
        String name,
        String address,
        boolean active) {

    /** Requires the theater's city to be loaded. */
    public static TheaterResponse from(Theater theater) {
        return new TheaterResponse(
                theater.getId(),
                theater.getCity().getId(),
                theater.getCity().getName(),
                theater.getName(),
                theater.getAddress(),
                theater.isActive());
    }
}
