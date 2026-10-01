package com.harshil.movieticketbooking.booking.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import com.harshil.movieticketbooking.show.domain.ShowSeat;
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
 * One seat within a booking, together with the price snapshot for it.
 * <p>
 * The row label, number, category, resolved day type and exact price charged
 * are copied here at hold time rather than read back through the seat and
 * pricing tables, so re-pricing a screen tomorrow cannot change what last
 * week's ticket was charged.
 */
@Entity
@Getter
@Builder
@Table(name = "booking_seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BookingSeat extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "booking_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_booking_seats_booking"))
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "show_seat_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_booking_seats_show_seat"))
    private ShowSeat showSeat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "seat_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_booking_seats_seat"))
    private Seat seat;

    @Column(columnDefinition = "VARCHAR(5)", name = "row_label", nullable = false, length = 5)
    private String rowLabel;

    @Column(columnDefinition = "INTEGER", name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "seat_category", nullable = false, length = 20)
    private SeatCategory seatCategory;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "day_type", nullable = false, length = 20)
    private DayType dayType;

    @Column(columnDefinition = "NUMERIC(10,2)", name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** Snapshots the seat's identity and its resolved price onto the booking. */
    public static BookingSeat snapshot(ShowSeat showSeat, Seat seat, DayType dayType, BigDecimal price) {
        return BookingSeat.builder()
                .showSeat(showSeat)
                .seat(seat)
                .rowLabel(seat.getRowLabel())
                .seatNumber(seat.getSeatNumber())
                .seatCategory(seat.getCategory())
                .dayType(dayType)
                .price(price)
                .build();
    }

    public String label() {
        return rowLabel + seatNumber;
    }

    void attachTo(Booking booking) {
        this.booking = booking;
    }
}
