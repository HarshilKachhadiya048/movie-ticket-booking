package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outcome of the second payment transaction.
 * <p>
 * Business failures are <em>returned</em>, not thrown. The transaction that
 * records "this payment was declined and the seats were released" has to
 * commit; throwing from inside it would roll that record back and leave the
 * booking stuck in PAYMENT_PENDING with its seats still held. The orchestrator
 * translates the outcome into an exception once the transaction has safely
 * committed.
 */
public record PaymentSettlement(
        Kind kind,
        BookingResponse booking,
        String failureReason,
        Compensation compensation) {

    public enum Kind {

        /** Paid, seats are BOOKED, booking is CONFIRMED. */
        CONFIRMED,

        /** Declined. Seats released, booking terminal in PAYMENT_FAILED. */
        DECLINED,

        /**
         * The hold lapsed while the gateway was being called, so the seats are
         * gone but the card was charged. The charge has to be given back; the
         * orchestrator issues a compensating refund outside the transaction.
         */
        HOLD_LAPSED_AFTER_CHARGE
    }

    /** What the orchestrator must hand back to the gateway. */
    public record Compensation(
            UUID paymentId,
            String gatewayReference,
            BigDecimal amount,
            String currency) {
    }

    public static PaymentSettlement confirmed(BookingResponse booking) {
        return new PaymentSettlement(Kind.CONFIRMED, booking, null, null);
    }

    public static PaymentSettlement declined(String failureReason) {
        return new PaymentSettlement(Kind.DECLINED, null, failureReason, null);
    }

    public static PaymentSettlement holdLapsedAfterCharge(Compensation compensation) {
        return new PaymentSettlement(Kind.HOLD_LAPSED_AFTER_CHARGE, null, null, compensation);
    }
}
