package com.harshil.movieticketbooking.pricing.domain;

import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.common.domain.BaseEntity;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.Theater;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One configured price for a (scope, seat category, day type) combination.
 * <p>
 * A rule is scoped at exactly one level - screen, theater, city, or global
 * when all three are null - and
 * {@link com.harshil.movieticketbooking.pricing.service.PricingService} picks
 * the most specific match. That gives a platform-wide default that a city,
 * a venue or a single premium auditorium can override, all as data. No price
 * and no weekend multiplier appears anywhere in Java.
 * <p>
 * Rules are not time-versioned, and deliberately so: bookings snapshot the
 * price they were charged onto {@code booking_seats}, so editing a rule
 * changes future bookings only. Adding validity windows here would add
 * overlap-resolution rules for no benefit the snapshot does not already give.
 */
@Entity
@Getter
@Builder
@Table(name = "pricing_rules")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PricingRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "city_id",
            foreignKey = @ForeignKey(name = "fk_pricing_rules_city"))
    private City city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "theater_id",
            foreignKey = @ForeignKey(name = "fk_pricing_rules_theater"))
    private Theater theater;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "screen_id",
            foreignKey = @ForeignKey(name = "fk_pricing_rules_screen"))
    private Screen screen;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "seat_category", nullable = false, length = 20)
    private SeatCategory seatCategory;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "day_type", nullable = false, length = 20)
    private DayType dayType;

    @Column(columnDefinition = "NUMERIC(10,2)", name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(columnDefinition = "BOOLEAN", name = "active", nullable = false)
    private boolean active;

    /** Higher wins when several rules match. */
    public PricingScope scope() {
        if (screen != null) {
            return PricingScope.SCREEN;
        }
        if (theater != null) {
            return PricingScope.THEATER;
        }
        if (city != null) {
            return PricingScope.CITY;
        }
        return PricingScope.GLOBAL;
    }

    public void updatePrice(BigDecimal price, boolean active) {
        this.price = price;
        this.active = active;
    }
}
