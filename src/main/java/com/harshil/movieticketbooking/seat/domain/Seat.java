package com.harshil.movieticketbooking.seat.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A physical seat in a screen's layout.
 * <p>
 * Reference data, not inventory: a seat says nothing about availability. Every
 * show materialises its own {@code show_seats} row per seat, and that row is
 * what booking locks and mutates. Keeping the two apart is what lets the same
 * physical seat be free for one show and booked for another.
 */
@Entity
@Getter
@Builder
@Table(name = "seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Seat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "screen_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_seats_screen"))
    private Screen screen;

    @Column(columnDefinition = "VARCHAR(5)", name = "row_label", nullable = false, length = 5)
    private String rowLabel;

    @Column(columnDefinition = "INTEGER", name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "category", nullable = false, length = 20)
    private SeatCategory category;

    @Column(columnDefinition = "BOOLEAN", name = "active", nullable = false)
    private boolean active;

    /** Customer-facing label, for example {@code A12}. */
    public String label() {
        return rowLabel + seatNumber;
    }
}
