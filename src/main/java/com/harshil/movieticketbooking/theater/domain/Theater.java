package com.harshil.movieticketbooking.theater.domain;

import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A venue in a city, owning one or more screens. */
@Entity
@Getter
@Builder
@Table(name = "theaters")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Theater extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "city_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_theaters_city"))
    private City city;

    @Column(columnDefinition = "VARCHAR(150)", name = "name", nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "VARCHAR(300)", name = "address", nullable = false, length = 300)
    private String address;

    @Column(columnDefinition = "BOOLEAN", name = "active", nullable = false)
    private boolean active;

    public void update(City city, String name, String address, boolean active) {
        this.city = city;
        this.name = name;
        this.address = address;
        this.active = active;
    }
}
