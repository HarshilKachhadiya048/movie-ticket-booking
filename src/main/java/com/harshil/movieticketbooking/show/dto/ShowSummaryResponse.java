package com.harshil.movieticketbooking.show.dto;

import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowStatus;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.Theater;
import java.time.Instant;
import java.util.UUID;

/**
 * A show with enough venue and film context to be displayed on its own.
 * <p>
 * Flattened deliberately: a client listing a schedule should not have to walk
 * show to screen to theater to city to render one row. {@code timeZone} is
 * included so a client can present the UTC {@code startsAt} in the cinema's
 * local time rather than the viewer's.
 */
public record ShowSummaryResponse(
        UUID showId,
        UUID movieId,
        String movieTitle,
        String language,
        String certification,
        int durationMinutes,
        Instant startsAt,
        Instant endsAt,
        ShowStatus status,
        UUID theaterId,
        String theaterName,
        UUID screenId,
        String screenName,
        UUID cityId,
        String cityName,
        String timeZone,
        Long availableSeats) {

    /** Requires the show's movie, screen, theater and city to be loaded. */
    public static ShowSummaryResponse from(Show show, Long availableSeats) {
        Screen screen = show.getScreen();
        Theater theater = screen.getTheater();
        return new ShowSummaryResponse(
                show.getId(),
                show.getMovie().getId(),
                show.getMovie().getTitle(),
                show.getMovie().getLanguage(),
                show.getMovie().getCertification(),
                show.getMovie().getDurationMinutes(),
                show.getStartsAt(),
                show.getEndsAt(),
                show.getStatus(),
                theater.getId(),
                theater.getName(),
                screen.getId(),
                screen.getName(),
                theater.getCity().getId(),
                theater.getCity().getName(),
                theater.getCity().getTimeZone(),
                availableSeats);
    }

    public static ShowSummaryResponse from(Show show) {
        return from(show, null);
    }
}
