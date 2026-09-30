package com.harshil.movieticketbooking.discount.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One live redemption of a discount code by one booking.
 * <p>
 * Rows are inserted when the hold is created and deleted when the booking is
 * released - expiry, payment failure or cancellation - so an abandoned hold
 * does not permanently consume somebody's promotional code. The unique
 * constraint on {@code booking_id} makes double redemption by a single booking
 * impossible, and counting rows per user enforces the per-user limit.
 */
@Entity
@Getter
@Builder
@Table(name = "discount_code_usages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DiscountCodeUsage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "discount_code_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_discount_code_usages_code"))
    private DiscountCode discountCode;

    @Column(columnDefinition = "UUID", name = "user_id", nullable = false)
    private UUID userId;

    @Column(columnDefinition = "UUID", name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(columnDefinition = "NUMERIC(12,2)", name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;
}
