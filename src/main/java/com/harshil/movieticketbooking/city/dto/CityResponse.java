package com.harshil.movieticketbooking.city.dto;

import com.harshil.movieticketbooking.city.domain.City;
import java.util.UUID;

/**
 * A city the platform operates in.
 * <p>
 * {@code timeZone} is exposed because it is not decoration: it determines
 * whether a show is priced at weekday or weekend rates, and it is what a
 * client needs to render a UTC show time in the cinema's local clock.
 */
public record CityResponse(
        UUID cityId,
        String name,
        String state,
        String country,
        String timeZone,
        boolean active) {

    public static CityResponse from(City city) {
        return new CityResponse(
                city.getId(),
                city.getName(),
                city.getState(),
                city.getCountry(),
                city.getTimeZone(),
                city.isActive());
    }
}
