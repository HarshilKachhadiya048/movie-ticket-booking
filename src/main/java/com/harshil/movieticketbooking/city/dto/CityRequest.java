package com.harshil.movieticketbooking.city.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create or update a city.
 *
 * @param timeZone an IANA zone id such as {@code Asia/Kolkata}. Validated
 *                 against {@link java.time.ZoneId} in the service rather than
 *                 by a regex, because only the JDK's own tz database knows
 *                 which ids are real.
 * @param active   optional on create, defaulting to true
 */
public record CityRequest(
        @NotBlank(message = "name is required")
        @Size(max = 120, message = "name must not exceed 120 characters") String name,

        @NotBlank(message = "state is required")
        @Size(max = 120, message = "state must not exceed 120 characters") String state,

        @NotBlank(message = "country is required")
        @Size(max = 80, message = "country must not exceed 80 characters") String country,

        @NotBlank(message = "timeZone is required")
        @Size(max = 60, message = "timeZone must not exceed 60 characters") String timeZone,

        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
