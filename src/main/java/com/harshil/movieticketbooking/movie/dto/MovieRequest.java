package com.harshil.movieticketbooking.movie.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Create or update a movie.
 *
 * @param durationMinutes capped at 24 hours; a longer runtime is a typo, and
 *                        the value is used to default a show's end time
 */
public record MovieRequest(
        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must not exceed 200 characters") String title,

        @NotBlank(message = "language is required")
        @Size(max = 50, message = "language must not exceed 50 characters") String language,

        @Size(max = 10, message = "certification must not exceed 10 characters") String certification,

        @NotNull(message = "durationMinutes is required")
        @Positive(message = "durationMinutes must be positive")
        @Max(value = 1440, message = "durationMinutes must not exceed 24 hours") Integer durationMinutes,

        LocalDate releaseDate,

        @Size(max = 4000, message = "synopsis must not exceed 4000 characters") String synopsis,

        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
