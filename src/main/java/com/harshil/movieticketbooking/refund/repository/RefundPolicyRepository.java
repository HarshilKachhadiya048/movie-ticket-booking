package com.harshil.movieticketbooking.refund.repository;

import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefundPolicyRepository extends JpaRepository<RefundPolicy, UUID> {

    /** The theater's own policy, with its bands, if one is configured. */
    @Query("""
            SELECT DISTINCT p
            FROM RefundPolicy p
            LEFT JOIN FETCH p.rules
            WHERE p.theater.id = :theaterId
              AND p.active = TRUE
            """)
    Optional<RefundPolicy> findActiveByTheaterId(@Param("theaterId") UUID theaterId);

    /** The single platform default, guaranteed unique by a partial index. */
    @Query("""
            SELECT DISTINCT p
            FROM RefundPolicy p
            LEFT JOIN FETCH p.rules
            WHERE p.defaultPolicy = TRUE
              AND p.active = TRUE
            """)
    Optional<RefundPolicy> findActiveDefault();

    @Query("""
            SELECT DISTINCT p
            FROM RefundPolicy p
            LEFT JOIN FETCH p.rules
            LEFT JOIN FETCH p.theater
            ORDER BY p.name ASC
            """)
    List<RefundPolicy> findAllWithRules();

    @Query("""
            SELECT DISTINCT p
            FROM RefundPolicy p
            LEFT JOIN FETCH p.rules
            WHERE p.id = :policyId
            """)
    Optional<RefundPolicy> findByIdWithRules(@Param("policyId") UUID policyId);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    boolean existsByTheaterId(UUID theaterId);
}
