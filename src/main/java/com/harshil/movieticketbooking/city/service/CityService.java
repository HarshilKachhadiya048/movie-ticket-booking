package com.harshil.movieticketbooking.city.service;

import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.city.dto.CityRequest;
import com.harshil.movieticketbooking.city.dto.CityResponse;
import com.harshil.movieticketbooking.city.repository.CityRepository;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Browsing and administration of cities. */
@Service
@RequiredArgsConstructor
public class CityService {

    private final CityRepository cityRepository;

    @Transactional(readOnly = true)
    public List<CityResponse> listActiveCities() {
        return cityRepository.findAllByActiveTrueOrderByNameAsc()
                .stream()
                .map(CityResponse::from)
                .toList();
    }

    /** Admin listing: includes deactivated cities. */
    @Transactional(readOnly = true)
    public List<CityResponse> listAllCities() {
        return cityRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(City::getName))
                .map(CityResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CityResponse getCity(UUID cityId) {
        return CityResponse.from(requireCity(cityId));
    }

    @Transactional
    public CityResponse createCity(CityRequest request) {
        validateTimeZone(request.timeZone());
        if (cityRepository.existsByNameIgnoreCaseAndStateIgnoreCase(request.name(), request.state())) {
            throw new DomainException(
                    ErrorCode.CITY_ALREADY_EXISTS,
                    "%s, %s already exists".formatted(request.name(), request.state()));
        }

        City city = cityRepository.save(City.builder()
                .name(request.name())
                .state(request.state())
                .country(request.country())
                .timeZone(request.timeZone())
                .active(request.activeOrDefault())
                .build());
        return CityResponse.from(city);
    }

    @Transactional
    public CityResponse updateCity(UUID cityId, CityRequest request) {
        validateTimeZone(request.timeZone());
        City city = requireCity(cityId);
        if (cityRepository.existsByNameIgnoreCaseAndStateIgnoreCaseAndIdNot(
                request.name(), request.state(), cityId)) {
            throw new DomainException(
                    ErrorCode.CITY_ALREADY_EXISTS,
                    "%s, %s already exists".formatted(request.name(), request.state()));
        }

        city.update(
                request.name(),
                request.state(),
                request.country(),
                request.timeZone(),
                request.activeOrDefault());
        return CityResponse.from(city);
    }

    /**
     * Deactivates rather than deletes.
     * <p>
     * Theaters, shows and historical bookings all reference a city. Removing
     * the row would either break those foreign keys or cascade into deleting
     * somebody's booking history, so a city is retired from listings instead.
     */
    @Transactional
    public void deactivateCity(UUID cityId) {
        City city = requireCity(cityId);
        city.update(
                city.getName(), city.getState(), city.getCountry(), city.getTimeZone(), false);
    }

    private City requireCity(UUID cityId) {
        return cityRepository.findById(cityId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.CITY_NOT_FOUND, "City %s does not exist".formatted(cityId)));
    }

    /** Only the JDK's tz database can say whether a zone id is real. */
    private static void validateTimeZone(String timeZone) {
        try {
            ZoneId.of(timeZone);
        } catch (RuntimeException ex) {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST, "'%s' is not a valid IANA time zone id".formatted(timeZone));
        }
    }
}
