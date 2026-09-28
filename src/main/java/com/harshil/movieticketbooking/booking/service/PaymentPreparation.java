package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outcome of the first payment transaction, handed to the orchestrator so it
 * knows whether to call the gateway at all.
 * <p>
 * The idempotency outcomes are returned rather than thrown because they are
 * decided inside a transaction that must <em>commit</em> - throwing would roll
 * back the very payment row that records the attempt.
 */
public record PaymentPreparation(
        Kind kind,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String methodToken,
        String idempotencyKey,
        String description,
        BookingResponse settledBooking,
        String failureReason) {

    public enum Kind {

        /** New attempt; the gateway should now be called. */
        PROCEED,

        /** This idempotency key already charged successfully. Replay the result. */
        ALREADY_SUCCEEDED,

        /** This idempotency key already failed. Replay the decline. */
        ALREADY_FAILED,

        /**
         * The hold had already lapsed. The booking has been expired and its
         * seats released, and that cleanup has to <em>commit</em> - which is
         * exactly why this is a returned outcome rather than a thrown
         * exception. Throwing from inside the transaction would roll back the
         * release and leave the seats stranded until the sweeper noticed.
         */
        HOLD_EXPIRED
    }

    public static PaymentPreparation proceed(
            UUID paymentId,
            BigDecimal amount,
            String currency,
            String methodToken,
            String idempotencyKey,
            String description) {
        return new PaymentPreparation(
                Kind.PROCEED, paymentId, amount, currency, methodToken, idempotencyKey, description, null, null);
    }

    public static PaymentPreparation alreadySucceeded(BookingResponse booking) {
        return new PaymentPreparation(
                Kind.ALREADY_SUCCEEDED, null, null, null, null, null, null, booking, null);
    }

    public static PaymentPreparation alreadyFailed(String failureReason) {
        return new PaymentPreparation(
                Kind.ALREADY_FAILED, null, null, null, null, null, null, null, failureReason);
    }

    public static PaymentPreparation holdExpired(String reason) {
        return new PaymentPreparation(
                Kind.HOLD_EXPIRED, null, null, null, null, null, null, null, reason);
    }
}
