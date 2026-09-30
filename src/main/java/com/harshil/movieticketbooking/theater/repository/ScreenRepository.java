package com.harshil.movieticketbooking.theater.repository;

import com.harshil.movieticketbooking.theater.domain.Screen;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ScreenRepository extends JpaRepository<Screen, UUID> {

    List<Screen> findAllByTheaterIdOrderByNameAsc(UUID theaterId);

    /**
     * Loads the whole venue chain in one query. Pricing resolution needs the
     * screen, its theater and the theater's city - the city supplies the time
     * zone that decides weekday versus weekend - so fetching them together
     * keeps the hold transaction to a single round trip for this data.
     */
    @Query("""
            SELECT s
            FROM Screen s
            JOIN FETCH s.theater t
            JOIN FETCH t.city
            WHERE s.id = :screenId
            """)
    Optional<Screen> findByIdWithTheaterAndCity(@Param("screenId") UUID screenId);

    boolean existsByTheaterIdAndNameIgnoreCase(UUID theaterId, String name);

    boolean existsByTheaterIdAndNameIgnoreCaseAndIdNot(UUID theaterId, String name, UUID id);
}
