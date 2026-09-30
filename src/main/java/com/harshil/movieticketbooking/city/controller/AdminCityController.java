package com.harshil.movieticketbooking.city.controller;

import com.harshil.movieticketbooking.city.dto.CityRequest;
import com.harshil.movieticketbooking.city.dto.CityResponse;
import com.harshil.movieticketbooking.city.service.CityService;
import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * City administration.
 * <p>
 * No {@code @PreAuthorize} here: everything under
 * {@link ApiEndpoints#ADMIN_PATTERN} is already restricted to
 * {@code ROLE_ADMIN} by the filter chain. Repeating the rule on each handler
 * would be two places to keep in sync, and the one that silently stopped
 * matching would be the dangerous one.
 * <p>
 * {@code DELETE} deactivates rather than removes. Theaters, shows and
 * historical bookings all reference a city, so a real delete would either
 * break those references or cascade into somebody's booking history.
 */
@RestController
@RequiredArgsConstructor
public class AdminCityController {

    private final CityService cityService;

    /** Unlike the customer listing, this includes deactivated cities. */
    @GetMapping(ApiEndpoints.Admin.Cities.ROOT)
    public List<CityResponse> listCities() {
        return cityService.listAllCities();
    }

    @PostMapping(ApiEndpoints.Admin.Cities.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public CityResponse createCity(@Valid @RequestBody CityRequest request) {
        return cityService.createCity(request);
    }

    @PutMapping(ApiEndpoints.Admin.Cities.BY_ID)
    public CityResponse updateCity(@PathVariable UUID cityId, @Valid @RequestBody CityRequest request) {
        return cityService.updateCity(cityId, request);
    }

    @DeleteMapping(ApiEndpoints.Admin.Cities.BY_ID)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateCity(@PathVariable UUID cityId) {
        cityService.deactivateCity(cityId);
    }
}
