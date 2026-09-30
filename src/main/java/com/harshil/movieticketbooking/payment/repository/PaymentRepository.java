package com.harshil.movieticketbooking.payment.repository;

import com.harshil.movieticketbooking.payment.domain.Payment;
import com.harshil.movieticketbooking.payment.domain.PaymentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** First stop on every payment request: an existing key means a replay. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findFirstByBookingIdAndStatus(UUID bookingId, PaymentStatus status);

    List<Payment> findAllByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    boolean existsByBookingIdAndStatus(UUID bookingId, PaymentStatus status);
}
