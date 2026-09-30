package com.harshil.movieticketbooking.discount.repository;

import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscountCodeRepository extends JpaRepository<DiscountCode, UUID> {

    Optional<DiscountCode> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    /**
     * Resolves a code to its id <em>without</em> loading the entity.
     * <p>
     * Deliberately a scalar projection. Loading the entity first and locking it
     * afterwards would leave the already-managed instance in the persistence
     * context, and Hibernate returns that cached instance rather than
     * overwriting it with what the locking select just read - so the redemption
     * check could run against a stale {@code usedCount}. Fetching only the id
     * means {@link #lockById} is the first and only load of the row, under the
     * lock.
     */
    @Query("""
            SELECT dc.id
            FROM DiscountCode dc
            WHERE UPPER(dc.code) = UPPER(:code)
            """)
    Optional<UUID> findIdByCode(@Param("code") String code);

    /**
     * Locks the code row for the rest of the transaction.
     * <p>
     * Redemption reads {@code usedCount}, compares it against the configured
     * limits and then increments it. Without this lock two customers could both
     * read "99 of 100 used" and both succeed. Taking the row lock serialises
     * redemptions of a single code, which also makes the per-user count safe to
     * read afterwards.
     * <p>
     * The booking flow always locks seats <em>before</em> reaching this call.
     * That fixed ordering - seats, then discount code - is what stops two
     * bookings deadlocking by grabbing the same two resources in opposite
     * order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT dc
            FROM DiscountCode dc
            WHERE dc.id = :discountCodeId
            """)
    Optional<DiscountCode> lockById(@Param("discountCodeId") UUID discountCodeId);
}
