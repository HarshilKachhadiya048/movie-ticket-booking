package com.harshil.movieticketbooking.booking.domain;

import com.harshil.movieticketbooking.common.domain.VersionedEntity;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.user.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A customer's booking, created the moment seats are held.
 * <p>
 * The full price breakdown is computed and frozen at hold time, so the
 * customer sees the exact payable amount before committing to pay and the
 * gateway is charged the amount that was quoted.
 * <p>
 * {@code holdToken} mirrors the token written onto every {@code show_seats}
 * row of this booking. Confirmation re-verifies that token against the locked
 * seat rows rather than trusting the booking alone - the seats are the
 * authority, this is a convenience copy.
 */
@Entity
@Getter
@Builder
@Table(name = "bookings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Booking extends VersionedEntity {

    @Column(columnDefinition = "VARCHAR(30)", name = "booking_reference", nullable = false, unique = true, length = 30)
    private String bookingReference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_bookings_user"))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "show_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_bookings_show"))
    private Show show;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(30)", name = "status", nullable = false, length = 30)
    private BookingStatus status;

    @Column(columnDefinition = "UUID", name = "hold_token")
    private UUID holdToken;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(columnDefinition = "INTEGER", name = "seat_count", nullable = false)
    private int seatCount;

    @Column(columnDefinition = "NUMERIC(12,2)", name = "subtotal_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "discount_code_id",
            foreignKey = @ForeignKey(name = "fk_bookings_discount_code"))
    private DiscountCode discountCode;

    @Column(columnDefinition = "NUMERIC(12,2)", name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(columnDefinition = "NUMERIC(12,2)", name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(columnDefinition = "VARCHAR(3)", name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "confirmed_at")
    private Instant confirmedAt;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "cancelled_at")
    private Instant cancelledAt;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Builder.Default
    @OrderBy("rowLabel ASC, seatNumber ASC")
    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BookingSeat> seats = new ArrayList<>();

    public List<BookingSeat> getSeats() {
        return Collections.unmodifiableList(seats);
    }

    public void addSeat(BookingSeat bookingSeat) {
        seats.add(bookingSeat);
        bookingSeat.attachTo(this);
    }

    public boolean isOwnedBy(UUID userId) {
        return user != null && user.getId().equals(userId);
    }

    public boolean isHoldLive(Instant now) {
        return status.holdsSeats() && holdExpiresAt != null && holdExpiresAt.isAfter(now);
    }

    public boolean isHoldExpired(Instant now) {
        return holdExpiresAt != null && !holdExpiresAt.isAfter(now);
    }

    // -----------------------------------------------------------------------
    // State transitions. Every mutation of `status` goes through transitionTo,
    // so an illegal move is a 409 rather than a corrupted booking.
    // -----------------------------------------------------------------------

    public void transitionTo(BookingStatus target) {
        if (status == target) {
            return;
        }
        if (!status.canTransitionTo(target)) {
            throw new DomainException(
                    ErrorCode.INVALID_BOOKING_STATE_TRANSITION,
                    "Booking %s cannot move from %s to %s".formatted(bookingReference, status, target));
        }
        this.status = target;
    }

    public void markPaymentPending() {
        transitionTo(BookingStatus.PAYMENT_PENDING);
    }

    public void confirm(Instant now) {
        transitionTo(BookingStatus.CONFIRMED);
        this.confirmedAt = now;
        clearHold();
    }

    public void markPaymentFailed() {
        transitionTo(BookingStatus.PAYMENT_FAILED);
        clearHold();
    }

    public void cancel(Instant now) {
        transitionTo(BookingStatus.CANCELLED);
        this.cancelledAt = now;
        clearHold();
    }

    /** Cancellation that returned money. Reached from CONFIRMED only. */
    public void markRefunded(Instant now) {
        transitionTo(BookingStatus.REFUNDED);
        this.cancelledAt = now;
        clearHold();
    }

    public void expire() {
        transitionTo(BookingStatus.EXPIRED);
        clearHold();
    }

    public void markReminderSent(Instant now) {
        this.reminderSentAt = now;
    }

    private void clearHold() {
        this.holdToken = null;
        this.holdExpiresAt = null;
    }
}
