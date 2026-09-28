package com.harshil.movieticketbooking.city.controller;

import com.harshil.movieticketbooking.city.dto.CityResponse;
import com.harshil.movieticketbooking.city.service.CityService;
import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.security.SecurityExpressions;
import com.harshil.movieticketbooking.theater.dto.TheaterResponse;
import com.harshil.movieticketbooking.theater.service.TheaterService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browsing cities and the theaters in them.
 * <p>
 * Authenticated rather than public. The brief lists browsing as a customer
 * capability, so it sits behind credentials; admins can browse too, which is
 * why the check is {@code isAuthenticated()} and not
 * {@code hasRole('CUSTOMER')}. Opening the catalogue to anonymous callers
 * would be a one-line change in {@code SecurityConfig}.
 */
@RestController
@RequiredArgsConstructor
public class CityController {

    private final CityService cityService;
    private final TheaterService theaterService;

    @GetMapping(ApiEndpoints.Cities.ROOT)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public List<CityResponse> listCities() {
        return cityService.listActiveCities();
    }

    @GetMapping(ApiEndpoints.Cities.BY_ID)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public CityResponse getCity(@PathVariable UUID cityId) {
        return cityService.getCity(cityId);
    }

    @GetMapping(ApiEndpoints.Cities.THEATERS)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public List<TheaterResponse> listTheaters(@PathVariable UUID cityId) {
        return theaterService.listTheatersInCity(cityId);
    }
}
