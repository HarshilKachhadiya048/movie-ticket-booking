package com.harshil.movieticketbooking.refund.domain;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.common.domain.VersionedEntity;
import com.harshil.movieticketbooking.payment.domain.Payment;
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
 * A refund issued against a cancelled booking.
 * <p>
 * The unique constraint on {@code booking_id} is what makes cancellation
 * idempotent at the storage layer: two concurrent cancels of the same booking
 * cannot both create a refund, whatever the application does.
 * <p>
 * The resolved policy, the matched band and the hours that were remaining are
 * all stored alongside the amount. Policies are editable, so without that
 * snapshot a refund issued last month could not be explained today.
 */
@Entity
@Getter
@Builder
@Table(name = "refunds")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Refund extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "booking_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_refunds_booking"))
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "payment_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_refunds_payment"))
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "refund_policy_id",
            foreignKey = @ForeignKey(name = "fk_refunds_policy"))
    private RefundPolicy refundPolicy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "refund_policy_rule_id",
            foreignKey = @ForeignKey(name = "fk_refunds_policy_rule"))
    private RefundPolicyRule refundPolicyRule;

    @Column(columnDefinition = "NUMERIC(12,2)", name = "original_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal originalAmount;

    @Column(columnDefinition = "NUMERIC(5,2)", name = "refund_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal refundPercentage;

    @Column(columnDefinition = "NUMERIC(12,2)", name = "refund_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(columnDefinition = "VARCHAR(3)", name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(columnDefinition = "NUMERIC(10,2)", name = "hours_before_show", nullable = false, precision = 10, scale = 2)
    private BigDecimal hoursBeforeShow;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(columnDefinition = "VARCHAR(100)", name = "gateway_reference", length = 100)
    private String gatewayReference;

    @Column(columnDefinition = "VARCHAR(255)", name = "failure_reason", length = 255)
    private String failureReason;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "processed_at")
    private Instant processedAt;

    public void markCompleted(String gatewayReference, Instant now) {
        this.status = RefundStatus.COMPLETED;
        this.gatewayReference = gatewayReference;
        this.failureReason = null;
        this.processedAt = now;
    }

    public void markFailed(String failureReason, Instant now) {
        this.status = RefundStatus.FAILED;
        this.failureReason = failureReason == null || failureReason.length() <= 255
                ? failureReason
                : failureReason.substring(0, 255);
        this.processedAt = now;
    }

    public boolean isZeroValue() {
        return refundAmount == null || refundAmount.signum() == 0;
    }
}
