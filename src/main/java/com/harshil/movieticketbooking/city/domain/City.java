package com.harshil.movieticketbooking.city.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A city the platform operates in.
 * <p>
 * {@code timeZone} is business logic, not presentation. Shows are stored as
 * absolute UTC instants, but "is this a weekend show?" is a question about the
 * local calendar: a 00:30 Saturday show in Mumbai is 19:00 Friday in UTC.
 * Pricing resolves the day type by projecting the show's instant into this
 * zone, so a platform spanning time zones prices each city correctly.
 */
@Entity
@Getter
@Builder
@Table(name = "cities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class City extends BaseEntity {

    @Column(columnDefinition = "VARCHAR(120)", name = "name", nullable = false, length = 120)
    private String name;

    @Column(columnDefinition = "VARCHAR(120)", name = "state", nullable = false, length = 120)
    private String state;

    @Column(columnDefinition = "VARCHAR(80)", name = "country", nullable = false, length = 80)
    private String country;

    @Column(columnDefinition = "VARCHAR(60)", name = "time_zone", nullable = false, length = 60)
    private String timeZone;

    @Column(columnDefinition = "BOOLEAN", name = "active", nullable = false)
    private boolean active;

    /** Validated on the way in by {@code CityService}, so this cannot throw. */
    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }

    public void update(String name, String state, String country, String timeZone, boolean active) {
        this.name = name;
        this.state = state;
        this.country = country;
        this.timeZone = timeZone;
        this.active = active;
    }
}
