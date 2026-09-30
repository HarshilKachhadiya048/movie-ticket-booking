package com.harshil.movieticketbooking.theater.repository;

import com.harshil.movieticketbooking.theater.domain.Theater;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TheaterRepository extends JpaRepository<Theater, UUID> {

    List<Theater> findAllByCityIdAndActiveTrueOrderByNameAsc(UUID cityId);

    /** Joins the city so callers can read its time zone without a second query. */
    @Query("""
            SELECT t
            FROM Theater t
            JOIN FETCH t.city
            WHERE t.id = :theaterId
            """)
    Optional<Theater> findByIdWithCity(@Param("theaterId") UUID theaterId);

    boolean existsByCityIdAndNameIgnoreCase(UUID cityId, String name);

    boolean existsByCityIdAndNameIgnoreCaseAndIdNot(UUID cityId, String name, UUID id);
}
