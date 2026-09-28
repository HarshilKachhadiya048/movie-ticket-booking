package com.harshil.movieticketbooking.movie.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.common.api.PageResponse;
import com.harshil.movieticketbooking.movie.dto.MovieResponse;
import com.harshil.movieticketbooking.movie.service.MovieService;
import com.harshil.movieticketbooking.security.SecurityExpressions;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;

    @GetMapping(ApiEndpoints.Movies.ROOT)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public PageResponse<MovieResponse> listMovies(
            @PageableDefault(size = 20, sort = "title", direction = Sort.Direction.ASC) Pageable pageable) {
        return movieService.listActiveMovies(pageable);
    }

    @GetMapping(ApiEndpoints.Movies.BY_ID)
    @PreAuthorize(SecurityExpressions.IS_AUTHENTICATED)
    public MovieResponse getMovie(@PathVariable UUID movieId) {
        return movieService.getMovie(movieId);
    }
}
