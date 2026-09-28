package com.harshil.movieticketbooking.movie.repository;

import com.harshil.movieticketbooking.movie.domain.Movie;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MovieRepository extends JpaRepository<Movie, UUID> {

    Page<Movie> findAllByActiveTrue(Pageable pageable);

    boolean existsByTitleIgnoreCaseAndLanguageIgnoreCase(String title, String language);

    boolean existsByTitleIgnoreCaseAndLanguageIgnoreCaseAndIdNot(String title, String language, UUID id);
}
