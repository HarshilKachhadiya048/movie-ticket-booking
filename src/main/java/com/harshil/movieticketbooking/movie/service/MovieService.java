package com.harshil.movieticketbooking.movie.service;

import com.harshil.movieticketbooking.common.api.PageResponse;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.movie.domain.Movie;
import com.harshil.movieticketbooking.movie.dto.MovieRequest;
import com.harshil.movieticketbooking.movie.dto.MovieResponse;
import com.harshil.movieticketbooking.movie.repository.MovieRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieRepository movieRepository;

    @Transactional(readOnly = true)
    public PageResponse<MovieResponse> listActiveMovies(Pageable pageable) {
        return PageResponse.of(movieRepository.findAllByActiveTrue(pageable), MovieResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<MovieResponse> listAllMovies(Pageable pageable) {
        return PageResponse.of(movieRepository.findAll(pageable), MovieResponse::from);
    }

    @Transactional(readOnly = true)
    public MovieResponse getMovie(UUID movieId) {
        return MovieResponse.from(requireMovie(movieId));
    }

    @Transactional
    public MovieResponse createMovie(MovieRequest request) {
        if (movieRepository.existsByTitleIgnoreCaseAndLanguageIgnoreCase(request.title(), request.language())) {
            throw new DomainException(
                    ErrorCode.MOVIE_ALREADY_EXISTS,
                    "'%s' in %s already exists".formatted(request.title(), request.language()));
        }

        Movie movie = movieRepository.save(Movie.builder()
                .title(request.title())
                .language(request.language())
                .certification(request.certification())
                .durationMinutes(request.durationMinutes())
                .releaseDate(request.releaseDate())
                .synopsis(request.synopsis())
                .active(request.activeOrDefault())
                .build());
        return MovieResponse.from(movie);
    }

    @Transactional
    public MovieResponse updateMovie(UUID movieId, MovieRequest request) {
        Movie movie = requireMovie(movieId);
        if (movieRepository.existsByTitleIgnoreCaseAndLanguageIgnoreCaseAndIdNot(
                request.title(), request.language(), movieId)) {
            throw new DomainException(
                    ErrorCode.MOVIE_ALREADY_EXISTS,
                    "'%s' in %s already exists".formatted(request.title(), request.language()));
        }

        movie.update(
                request.title(),
                request.language(),
                request.certification(),
                request.durationMinutes(),
                request.releaseDate(),
                request.synopsis(),
                request.activeOrDefault());
        return MovieResponse.from(movie);
    }

    /** Retired from listings; existing shows and bookings keep referencing it. */
    @Transactional
    public void deactivateMovie(UUID movieId) {
        Movie movie = requireMovie(movieId);
        movie.update(
                movie.getTitle(),
                movie.getLanguage(),
                movie.getCertification(),
                movie.getDurationMinutes(),
                movie.getReleaseDate(),
                movie.getSynopsis(),
                false);
    }

    /** Shared with {@code ShowService}, which needs the entity to link a show. */
    @Transactional(readOnly = true)
    public Movie requireMovie(UUID movieId) {
        return movieRepository.findById(movieId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.MOVIE_NOT_FOUND, "Movie %s does not exist".formatted(movieId)));
    }
}
