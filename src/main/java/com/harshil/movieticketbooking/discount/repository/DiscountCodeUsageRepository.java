package com.harshil.movieticketbooking.discount.repository;

import com.harshil.movieticketbooking.discount.domain.DiscountCodeUsage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscountCodeUsageRepository extends JpaRepository<DiscountCodeUsage, UUID> {

    Optional<DiscountCodeUsage> findByBookingId(UUID bookingId);

    /**
     * Only safe to call while the discount code row is locked - otherwise a
     * concurrent redemption by the same user could slip in between this count
     * and the insert that follows it.
     */
    long countByDiscountCodeIdAndUserId(UUID discountCodeId, UUID userId);
}
