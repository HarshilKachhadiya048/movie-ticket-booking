package com.harshil.movieticketbooking.payment.domain;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.common.domain.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A payment attempt against a booking.
 * <p>
 * The row is written as {@link PaymentStatus#PENDING} and committed
 * <em>before</em> the gateway is called, so a crash mid-charge leaves evidence
 * that the attempt happened rather than losing it.
 * <p>
 * Two database guarantees do the heavy lifting here:
 * {@code uq_payments_idempotency_key} means a retried request with the same
 * key can never produce a second charge, and the partial unique index
 * {@code ux_payments_booking_successful} means a booking can never accumulate
 * two successful payments even if the application logic were wrong.
 */
@Entity
@Getter
@Builder
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Payment extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "booking_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_payments_booking"))
    private Booking booking;

    @Column(columnDefinition = "VARCHAR(100)", name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(columnDefinition = "NUMERIC(12,2)", name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(columnDefinition = "VARCHAR(3)", name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(columnDefinition = "VARCHAR(100)", name = "method_token", nullable = false, length = 100)
    private String methodToken;

    @Column(columnDefinition = "VARCHAR(100)", name = "gateway_reference", length = 100)
    private String gatewayReference;

    @Column(columnDefinition = "VARCHAR(255)", name = "failure_reason", length = 255)
    private String failureReason;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "processed_at")
    private Instant processedAt;

    public static Payment pending(
            Booking booking,
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            String methodToken) {
        return Payment.builder()
                .booking(booking)
                .idempotencyKey(idempotencyKey)
                .amount(amount)
                .currency(currency)
                .status(PaymentStatus.PENDING)
                .methodToken(methodToken)
                .build();
    }

    public void markSuccess(String gatewayReference, Instant now) {
        this.status = PaymentStatus.SUCCESS;
        this.gatewayReference = gatewayReference;
        this.failureReason = null;
        this.processedAt = now;
    }

    public void markFailed(String failureReason, Instant now) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = truncate(failureReason);
        this.processedAt = now;
    }

    /**
     * The gateway could not be reached, so the outcome is unknown.
     * <p>
     * Left PENDING on purpose. Marking it FAILED would assert that no money
     * moved, which is exactly what is not known - the request may have been
     * processed and the response lost. The reason is recorded for
     * reconciliation, and the booking's hold is allowed to lapse normally so
     * the seats are not held indefinitely.
     */
    public void recordGatewayUnavailable(String reason) {
        this.failureReason = truncate(reason);
    }

    public boolean isSuccessful() {
        return status == PaymentStatus.SUCCESS;
    }

    /** Keeps a verbose gateway message inside the column's 255 characters. */
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }
}
