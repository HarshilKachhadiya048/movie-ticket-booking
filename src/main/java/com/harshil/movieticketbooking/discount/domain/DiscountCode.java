package com.harshil.movieticketbooking.discount.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import com.harshil.movieticketbooking.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A promotional code with its own configured validity and limits.
 * <p>
 * {@code usedCount} mirrors the number of live {@link DiscountCodeUsage} rows.
 * It is only ever changed by {@link #recordRedemption()} /
 * {@link #releaseRedemption()}, and only while the caller holds a pessimistic
 * row lock on this code - see
 * {@link com.harshil.movieticketbooking.discount.service.DiscountService}.
 * That lock is what stops the last remaining use of a code being handed to two
 * simultaneous customers.
 */
@Entity
@Getter
@Builder
@Table(name = "discount_codes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DiscountCode extends BaseEntity {

    @Column(columnDefinition = "VARCHAR(40)", name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(columnDefinition = "VARCHAR(255)", name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(columnDefinition = "NUMERIC(10,2)", name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(columnDefinition = "NUMERIC(10,2)", name = "max_discount_amount", precision = 10, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(columnDefinition = "NUMERIC(10,2)", name = "min_booking_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal minBookingAmount;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(columnDefinition = "INTEGER", name = "usage_limit")
    private Integer usageLimit;

    @Column(columnDefinition = "INTEGER", name = "per_user_limit")
    private Integer perUserLimit;

    @Column(columnDefinition = "INTEGER", name = "used_count", nullable = false)
    private int usedCount;

    @Column(columnDefinition = "BOOLEAN", name = "active", nullable = false)
    private boolean active;

    /** {@code validFrom} inclusive, {@code validUntil} exclusive. */
    public boolean isWithinValidityWindow(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    public boolean meetsMinimumBookingAmount(BigDecimal subtotal) {
        return Money.normalize(subtotal).compareTo(Money.normalize(minBookingAmount)) >= 0;
    }

    public boolean hasRemainingUses() {
        return usageLimit == null || usedCount < usageLimit;
    }

    /**
     * The discount this code yields on {@code subtotal}, after applying the
     * configured cap and clamping so a discount can never exceed the amount
     * owed. A fixed-amount code worth more than the booking simply makes it
     * free rather than producing a negative total.
     */
    public BigDecimal discountFor(BigDecimal subtotal) {
        BigDecimal raw = discountType.rawDiscountOn(subtotal, discountValue);
        if (maxDiscountAmount != null) {
            raw = Money.capped(raw, maxDiscountAmount);
        }
        return Money.capped(raw, subtotal);
    }

    /** Call only while holding the row lock on this code. */
    public void recordRedemption() {
        this.usedCount++;
    }

    /** Call only while holding the row lock on this code. */
    public void releaseRedemption() {
        if (this.usedCount > 0) {
            this.usedCount--;
        }
    }

    public void update(
            String description,
            DiscountType discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscountAmount,
            BigDecimal minBookingAmount,
            Instant validFrom,
            Instant validUntil,
            Integer usageLimit,
            Integer perUserLimit,
            boolean active) {
        this.description = description;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.maxDiscountAmount = maxDiscountAmount;
        this.minBookingAmount = minBookingAmount;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.usageLimit = usageLimit;
        this.perUserLimit = perUserLimit;
        this.active = active;
    }
}
