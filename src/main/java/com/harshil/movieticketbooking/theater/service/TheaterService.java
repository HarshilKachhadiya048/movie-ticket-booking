package com.harshil.movieticketbooking.theater.service;

import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.city.repository.CityRepository;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.theater.domain.Theater;
import com.harshil.movieticketbooking.theater.dto.TheaterRequest;
import com.harshil.movieticketbooking.theater.dto.TheaterResponse;
import com.harshil.movieticketbooking.theater.repository.TheaterRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TheaterService {

    private final TheaterRepository theaterRepository;
    private final CityRepository cityRepository;

    @Transactional(readOnly = true)
    public List<TheaterResponse> listTheatersInCity(UUID cityId) {
        if (!cityRepository.existsById(cityId)) {
            throw new DomainException(
                    ErrorCode.CITY_NOT_FOUND, "City %s does not exist".formatted(cityId));
        }
        return theaterRepository.findAllByCityIdAndActiveTrueOrderByNameAsc(cityId)
                .stream()
                .map(TheaterResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TheaterResponse getTheater(UUID theaterId) {
        return TheaterResponse.from(requireTheaterWithCity(theaterId));
    }

    @Transactional
    public TheaterResponse createTheater(TheaterRequest request) {
        City city = requireCity(request.cityId());
        if (theaterRepository.existsByCityIdAndNameIgnoreCase(city.getId(), request.name())) {
            throw new DomainException(
                    ErrorCode.THEATER_ALREADY_EXISTS,
                    "'%s' already exists in %s".formatted(request.name(), city.getName()));
        }

        Theater theater = theaterRepository.save(Theater.builder()
                .city(city)
                .name(request.name())
                .address(request.address())
                .active(request.activeOrDefault())
                .build());
        return TheaterResponse.from(theater);
    }

    @Transactional
    public TheaterResponse updateTheater(UUID theaterId, TheaterRequest request) {
        Theater theater = requireTheaterWithCity(theaterId);
        City city = requireCity(request.cityId());
        if (theaterRepository.existsByCityIdAndNameIgnoreCaseAndIdNot(
                city.getId(), request.name(), theaterId)) {
            throw new DomainException(
                    ErrorCode.THEATER_ALREADY_EXISTS,
                    "'%s' already exists in %s".formatted(request.name(), city.getName()));
        }

        theater.update(city, request.name(), request.address(), request.activeOrDefault());
        return TheaterResponse.from(theater);
    }

    @Transactional
    public void deactivateTheater(UUID theaterId) {
        Theater theater = requireTheaterWithCity(theaterId);
        theater.update(theater.getCity(), theater.getName(), theater.getAddress(), false);
    }

    /** Shared with {@code ScreenService} and {@code RefundPolicyService}. */
    @Transactional(readOnly = true)
    public Theater requireTheaterWithCity(UUID theaterId) {
        return theaterRepository.findByIdWithCity(theaterId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.THEATER_NOT_FOUND, "Theater %s does not exist".formatted(theaterId)));
    }

    private City requireCity(UUID cityId) {
        return cityRepository.findById(cityId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.CITY_NOT_FOUND, "City %s does not exist".formatted(cityId)));
    }
}
