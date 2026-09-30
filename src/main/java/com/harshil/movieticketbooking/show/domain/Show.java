package com.harshil.movieticketbooking.show.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import com.harshil.movieticketbooking.movie.domain.Movie;
import com.harshil.movieticketbooking.theater.domain.Screen;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One screening of a movie on a screen at a point in time.
 * <p>
 * Start and end are absolute UTC instants. Anything that needs a calendar
 * answer - the day type used for weekend pricing - converts through the city's
 * own zone via {@link #localDate(ZoneId)} rather than assuming the server's.
 */
@Entity
@Getter
@Builder
@Table(name = "shows")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Show extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "screen_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_shows_screen"))
    private Screen screen;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "movie_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_shows_movie"))
    private Movie movie;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "status", nullable = false, length = 20)
    private ShowStatus status;

    /** The calendar date this show falls on in the given zone. */
    public LocalDate localDate(ZoneId zoneId) {
        return startsAt.atZone(zoneId).toLocalDate();
    }

    public boolean hasStarted(Instant now) {
        return !startsAt.isAfter(now);
    }

    /** New holds are only accepted for a scheduled show that has not begun. */
    public boolean isBookable(Instant now) {
        return status.isOpenForBooking() && !hasStarted(now);
    }

    /** Signed: negative once the show has started. Drives refund band selection. */
    public Duration timeUntilStart(Instant now) {
        return Duration.between(now, startsAt);
    }

    public void reschedule(Instant startsAt, Instant endsAt) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public void changeStatus(ShowStatus status) {
        this.status = status;
    }
}
