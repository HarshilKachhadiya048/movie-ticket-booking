package com.harshil.movieticketbooking.theater.dto;

import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.ScreenType;
import java.util.UUID;

public record ScreenResponse(
        UUID screenId,
        UUID theaterId,
        String theaterName,
        String name,
        ScreenType screenType,
        boolean active,
        Long seatCount) {

    public static ScreenResponse from(Screen screen, Long seatCount) {
        return new ScreenResponse(
                screen.getId(),
                screen.getTheater().getId(),
                screen.getTheater().getName(),
                screen.getName(),
                screen.getScreenType(),
                screen.isActive(),
                seatCount);
    }
}
