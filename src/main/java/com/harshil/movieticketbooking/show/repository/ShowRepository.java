package com.harshil.movieticketbooking.show.repository;

import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ShowRepository extends JpaRepository<Show, UUID> {

    /**
     * Loads a show with everything pricing and presentation need: the movie,
     * the screen, its theater and the theater's city. The city is what supplies
     * the time zone used to decide weekday versus weekend, so this one query
     * covers the whole hold path.
     */
    @Query("""
            SELECT s
            FROM Show s
            JOIN FETCH s.movie
            JOIN FETCH s.screen screen
            JOIN FETCH screen.theater theater
            JOIN FETCH theater.city
            WHERE s.id = :showId
            """)
    Optional<Show> findByIdWithVenueAndMovie(@Param("showId") UUID showId);

    @Query("""
            SELECT s
            FROM Show s
            JOIN FETCH s.movie
            JOIN FETCH s.screen screen
            JOIN FETCH screen.theater theater
            JOIN FETCH theater.city
            WHERE theater.id = :theaterId
              AND s.startsAt >= :from
              AND s.startsAt < :to
              AND s.status = :status
            ORDER BY s.startsAt ASC
            """)
    List<Show> findScheduleForTheater(
            @Param("theaterId") UUID theaterId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("status") ShowStatus status);

    /**
     * Detects a clash with an existing show on the same screen. Two shows
     * overlap when each starts before the other ends; the screen can only play
     * one film at a time.
     */
    @Query("""
            SELECT COUNT(s) > 0
            FROM Show s
            WHERE s.screen.id = :screenId
              AND s.status <> :cancelled
              AND s.startsAt < :endsAt
              AND s.endsAt > :startsAt
              AND (:excludedShowId IS NULL OR s.id <> :excludedShowId)
            """)
    boolean overlapsExistingShow(
            @Param("screenId") UUID screenId,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("cancelled") ShowStatus cancelled,
            @Param("excludedShowId") UUID excludedShowId);

    List<Show> findAllByScreenIdOrderByStartsAtAsc(UUID screenId);
}
