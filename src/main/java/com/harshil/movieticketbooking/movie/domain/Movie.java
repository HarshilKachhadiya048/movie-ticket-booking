package com.harshil.movieticketbooking.movie.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A film that can be scheduled as a show.
 * <p>
 * The brief's minimum table list does not name movies, but a show is a
 * screening <em>of</em> something and the customer-facing listing has to say
 * what. Modelling it as its own entity avoids copying title, language and
 * runtime onto every show row. See README "Assumptions".
 * <p>
 * {@code releaseDate} is a {@link LocalDate}, not an instant: a release date
 * is a calendar fact with no time-of-day and no zone.
 */
@Entity
@Getter
@Builder
@Table(name = "movies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Movie extends BaseEntity {

    @Column(columnDefinition = "VARCHAR(200)", name = "title", nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "VARCHAR(50)", name = "language", nullable = false, length = 50)
    private String language;

    @Column(columnDefinition = "VARCHAR(10)", name = "certification", length = 10)
    private String certification;

    @Column(columnDefinition = "INTEGER", name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(columnDefinition = "DATE", name = "release_date")
    private LocalDate releaseDate;

    @Column(columnDefinition = "TEXT", name = "synopsis")
    private String synopsis;

    @Column(columnDefinition = "BOOLEAN", name = "active", nullable = false)
    private boolean active;

    public void update(
            String title,
            String language,
            String certification,
            int durationMinutes,
            LocalDate releaseDate,
            String synopsis,
            boolean active) {
        this.title = title;
        this.language = language;
        this.certification = certification;
        this.durationMinutes = durationMinutes;
        this.releaseDate = releaseDate;
        this.synopsis = synopsis;
        this.active = active;
    }
}
