package com.harshil.movieticketbooking.movie.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.common.api.PageResponse;
import com.harshil.movieticketbooking.movie.dto.MovieRequest;
import com.harshil.movieticketbooking.movie.dto.MovieResponse;
import com.harshil.movieticketbooking.movie.service.MovieService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
public class AdminMovieController {

    private final MovieService movieService;

    @GetMapping(ApiEndpoints.Admin.Movies.ROOT)
    public PageResponse<MovieResponse> listMovies(
            @PageableDefault(size = 20, sort = "title", direction = Sort.Direction.ASC) Pageable pageable) {
        return movieService.listAllMovies(pageable);
    }

    @PostMapping(ApiEndpoints.Admin.Movies.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public MovieResponse createMovie(@Valid @RequestBody MovieRequest request) {
        return movieService.createMovie(request);
    }

    @PutMapping(ApiEndpoints.Admin.Movies.BY_ID)
    public MovieResponse updateMovie(@PathVariable UUID movieId, @Valid @RequestBody MovieRequest request) {
        return movieService.updateMovie(movieId, request);
    }

    @DeleteMapping(ApiEndpoints.Admin.Movies.BY_ID)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateMovie(@PathVariable UUID movieId) {
        movieService.deactivateMovie(movieId);
    }
}
