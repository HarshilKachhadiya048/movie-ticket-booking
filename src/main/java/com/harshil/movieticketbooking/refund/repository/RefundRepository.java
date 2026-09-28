package com.harshil.movieticketbooking.refund.repository;

import com.harshil.movieticketbooking.refund.domain.Refund;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    /**
     * One refund per booking, backed by a unique constraint. Finding an
     * existing row is how a repeated cancellation request returns the original
     * outcome instead of issuing a second refund.
     */
    Optional<Refund> findByBookingId(UUID bookingId);

    boolean existsByBookingId(UUID bookingId);
}
