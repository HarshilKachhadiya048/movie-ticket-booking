package com.harshil.movieticketbooking.theater.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.theater.dto.TheaterRequest;
import com.harshil.movieticketbooking.theater.dto.TheaterResponse;
import com.harshil.movieticketbooking.theater.service.TheaterService;
import jakarta.validation.Valid;
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

@RestController
@RequiredArgsConstructor
public class AdminTheaterController {

    private final TheaterService theaterService;

    @GetMapping(ApiEndpoints.Admin.Theaters.BY_ID)
    public TheaterResponse getTheater(@PathVariable UUID theaterId) {
        return theaterService.getTheater(theaterId);
    }

    @PostMapping(ApiEndpoints.Admin.Theaters.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public TheaterResponse createTheater(@Valid @RequestBody TheaterRequest request) {
        return theaterService.createTheater(request);
    }

    @PutMapping(ApiEndpoints.Admin.Theaters.BY_ID)
    public TheaterResponse updateTheater(
            @PathVariable UUID theaterId,
            @Valid @RequestBody TheaterRequest request) {
        return theaterService.updateTheater(theaterId, request);
    }

    @DeleteMapping(ApiEndpoints.Admin.Theaters.BY_ID)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateTheater(@PathVariable UUID theaterId) {
        theaterService.deactivateTheater(theaterId);
    }
}
