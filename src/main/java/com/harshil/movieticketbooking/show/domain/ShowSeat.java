package com.harshil.movieticketbooking.show.domain;

import com.harshil.movieticketbooking.common.domain.VersionedEntity;
import com.harshil.movieticketbooking.seat.domain.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Per-show seat inventory. The single source of truth for who has which seat.
 * <p>
 * This is the only row the booking path locks, and every state change happens
 * while the caller holds {@code SELECT ... FOR UPDATE} on it. The class is
 * written so that the invariants are enforced here rather than trusted to
 * callers:
 * <ul>
 *     <li>{@link #hold} refuses to overwrite a live hold or a booked seat, so
 *     a caller that forgot to check cannot silently steal a seat.</li>
 *     <li>{@link #release} and {@link #confirm} clear <em>every</em> hold
 *     column, matching the {@code ck_show_seats_hold_fields} check constraint;
 *     a half-cleared row is rejected by the database.</li>
 *     <li>{@link #effectiveStatus} applies hold expiry lazily, so an expired
 *     hold is bookable the instant it lapses - the cleanup scheduler is a
 *     tidy-up, not a precondition.</li>
 * </ul>
 * <p>
 * {@code heldByUserId} is a bare UUID rather than a {@code @ManyToOne}: the
 * hold path only ever compares it, and mapping an association would pull a
 * user row into the hottest transaction in the system for no benefit. The
 * foreign key still exists in the schema.
 */
@Entity
@Getter
@Builder
@Table(name = "show_seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShowSeat extends VersionedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "show_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_show_seats_show"))
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "seat_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_show_seats_seat"))
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "status", nullable = false, length = 20)
    private ShowSeatStatus status;

    @Column(columnDefinition = "UUID", name = "hold_token")
    private UUID holdToken;

    @Column(columnDefinition = "UUID", name = "held_by_user_id")
    private UUID heldByUserId;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "hold_expires_at")
    private Instant holdExpiresAt;

    /** Creates a fresh AVAILABLE inventory row for a show. */
    public static ShowSeat available(Show show, Seat seat) {
        return ShowSeat.builder()
                .show(show)
                .seat(seat)
                .status(ShowSeatStatus.AVAILABLE)
                .build();
    }

    /**
     * The status as of {@code now}, with hold expiry applied.
     * <p>
     * A HELD row whose expiry has passed reports AVAILABLE. This is the lazy
     * half of hold expiration: because it is evaluated inside the transaction
     * that already holds the row lock, correctness never depends on the
     * cleanup scheduler having run.
     */
    public ShowSeatStatus effectiveStatus(Instant now) {
        if (status == ShowSeatStatus.HELD && isHoldExpired(now)) {
            return ShowSeatStatus.AVAILABLE;
        }
        return status;
    }

    public boolean isHoldExpired(Instant now) {
        return holdExpiresAt != null && !holdExpiresAt.isAfter(now);
    }

    public boolean isAvailableAt(Instant now) {
        return effectiveStatus(now) == ShowSeatStatus.AVAILABLE;
    }

    public boolean isBooked() {
        return status == ShowSeatStatus.BOOKED;
    }

    /** True only for a hold that is both this token's and still live. */
    public boolean isValidHold(UUID token, Instant now) {
        return isHeldUnder(token) && !isHoldExpired(now);
    }

    /**
     * True when this row is still held under {@code token}, expired or not.
     * <p>
     * This is the check the release paths must use, not {@code isValidHold}.
     * Once a hold lapses the seat can legitimately be taken by somebody else
     * through lazy expiry, and that new hold carries a different token.
     * Releasing on status alone would hand a seat back to the pool that
     * another customer is now holding.
     */
    public boolean isHeldUnder(UUID token) {
        return status == ShowSeatStatus.HELD
                && token != null
                && token.equals(holdToken);
    }

    /**
     * Takes the seat for {@code userId} until {@code expiresAt}.
     *
     * @throws IllegalStateException if the seat is not available as of
     *                               {@code now}. Callers are expected to have
     *                               checked already and reported a proper 409;
     *                               reaching this means a bug, not a race, so
     *                               it fails loudly rather than overwriting.
     */
    public void hold(UUID token, UUID userId, Instant expiresAt, Instant now) {
        if (!isAvailableAt(now)) {
            throw new IllegalStateException(
                    "Cannot hold show seat %s in state %s".formatted(getId(), status));
        }
        this.status = ShowSeatStatus.HELD;
        this.holdToken = token;
        this.heldByUserId = userId;
        this.holdExpiresAt = expiresAt;
    }

    /** Turns a live hold into a confirmed booking. Clears all hold columns. */
    public void confirm() {
        this.status = ShowSeatStatus.BOOKED;
        clearHold();
    }

    /** Returns the seat to the pool. Safe to call on an already-free seat. */
    public void release() {
        this.status = ShowSeatStatus.AVAILABLE;
        clearHold();
    }

    private void clearHold() {
        this.holdToken = null;
        this.heldByUserId = null;
        this.holdExpiresAt = null;
    }
}
