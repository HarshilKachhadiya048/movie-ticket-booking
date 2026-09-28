package com.harshil.movieticketbooking.movie.dto;

import com.harshil.movieticketbooking.movie.domain.Movie;
import java.time.LocalDate;
import java.util.UUID;

public record MovieResponse(
        UUID movieId,
        String title,
        String language,
        String certification,
        int durationMinutes,
        LocalDate releaseDate,
        String synopsis,
        boolean active) {

    public static MovieResponse from(Movie movie) {
        return new MovieResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getLanguage(),
                movie.getCertification(),
                movie.getDurationMinutes(),
                movie.getReleaseDate(),
                movie.getSynopsis(),
                movie.isActive());
    }
}
