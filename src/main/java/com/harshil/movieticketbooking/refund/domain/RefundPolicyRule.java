package com.harshil.movieticketbooking.refund.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One band of a refund policy: "if this many hours remain before the show,
 * refund this percentage".
 * <p>
 * Bands are half-open, {@code [min, max)}, with a null {@code max} meaning
 * unbounded. Half-open intervals mean adjacent bands share an endpoint without
 * either overlapping or leaving a gap, so a cancellation at exactly 24 hours
 * has precisely one answer.
 * <p>
 * The brief's example ladder becomes three rows -
 * {@code [0,12) = 0%}, {@code [12,24) = 50%}, {@code [24,∞) = 100%} - and is
 * seed data, not code.
 */
@Entity
@Getter
@Builder
@Table(name = "refund_policy_rules")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RefundPolicyRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "refund_policy_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_refund_policy_rules_policy"))
    private RefundPolicy refundPolicy;

    @Column(columnDefinition = "INTEGER", name = "min_hours_before_show", nullable = false)
    private int minHoursBeforeShow;

    /** {@code null} means this band extends indefinitely. */
    @Column(columnDefinition = "INTEGER", name = "max_hours_before_show")
    private Integer maxHoursBeforeShow;

    @Column(columnDefinition = "NUMERIC(5,2)", name = "refund_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal refundPercentage;

    public boolean matches(BigDecimal hoursBeforeShow) {
        if (hoursBeforeShow.compareTo(BigDecimal.valueOf(minHoursBeforeShow)) < 0) {
            return false;
        }
        return maxHoursBeforeShow == null
                || hoursBeforeShow.compareTo(BigDecimal.valueOf(maxHoursBeforeShow)) < 0;
    }

    void attachTo(RefundPolicy refundPolicy) {
        this.refundPolicy = refundPolicy;
    }
}
