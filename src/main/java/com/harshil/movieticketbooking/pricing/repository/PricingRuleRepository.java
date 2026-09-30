package com.harshil.movieticketbooking.pricing.repository;

import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.pricing.domain.PricingRule;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

    /**
     * Every active rule that could apply to a seat on this screen, across all
     * four scope levels, for the given day type.
     * <p>
     * One query rather than four cascading lookups: a screen has at most a
     * handful of candidate rules, and pulling them together lets
     * {@code PricingService} pick the most specific match for every seat in the
     * booking from a single in-memory index. The hold transaction therefore
     * issues one pricing query regardless of how many seats are requested.
     */
    @Query("""
            SELECT pr
            FROM PricingRule pr
            WHERE pr.active = TRUE
              AND pr.dayType = :dayType
              AND (
                    pr.screen.id = :screenId
                 OR pr.theater.id = :theaterId
                 OR pr.city.id = :cityId
                 OR (pr.screen IS NULL AND pr.theater IS NULL AND pr.city IS NULL)
              )
            """)
    List<PricingRule> findCandidateRules(
            @Param("screenId") UUID screenId,
            @Param("theaterId") UUID theaterId,
            @Param("cityId") UUID cityId,
            @Param("dayType") DayType dayType);

    @Query("""
            SELECT pr
            FROM PricingRule pr
            LEFT JOIN FETCH pr.city
            LEFT JOIN FETCH pr.theater
            LEFT JOIN FETCH pr.screen
            ORDER BY pr.seatCategory ASC, pr.dayType ASC
            """)
    List<PricingRule> findAllWithScope();

    @Query("""
            SELECT COUNT(pr) > 0
            FROM PricingRule pr
            WHERE pr.seatCategory = :seatCategory
              AND pr.dayType = :dayType
              AND (:cityId IS NULL AND pr.city IS NULL OR pr.city.id = :cityId)
              AND (:theaterId IS NULL AND pr.theater IS NULL OR pr.theater.id = :theaterId)
              AND (:screenId IS NULL AND pr.screen IS NULL OR pr.screen.id = :screenId)
              AND (:excludedId IS NULL OR pr.id <> :excludedId)
            """)
    boolean existsForScope(
            @Param("cityId") UUID cityId,
            @Param("theaterId") UUID theaterId,
            @Param("screenId") UUID screenId,
            @Param("seatCategory") SeatCategory seatCategory,
            @Param("dayType") DayType dayType,
            @Param("excludedId") UUID excludedId);
}
