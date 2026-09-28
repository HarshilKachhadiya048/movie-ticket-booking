package com.harshil.movieticketbooking.theater.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
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
 * An auditorium inside a theater. Owns the physical seat layout that every
 * show on this screen materialises into per-show inventory.
 */
@Entity
@Getter
@Builder
@Table(name = "screens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Screen extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "theater_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_screens_theater"))
    private Theater theater;

    @Column(columnDefinition = "VARCHAR(100)", name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(30)", name = "screen_type", nullable = false, length = 30)
    private ScreenType screenType;

    @Column(columnDefinition = "BOOLEAN", name = "active", nullable = false)
    private boolean active;

    public void update(String name, ScreenType screenType, boolean active) {
        this.name = name;
        this.screenType = screenType;
        this.active = active;
    }
}
