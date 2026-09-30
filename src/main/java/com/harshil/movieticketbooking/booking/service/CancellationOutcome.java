package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.dto.CancellationResponse;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of the cancellation transaction, telling the orchestrator whether a
 * gateway refund still has to be issued.
 * <p>
 * The booking is already cancelled and its seats already released by the time
 * this is returned. That ordering is deliberate: the customer's cancellation
 * is honoured, and the seats go back on sale, without waiting on a payment
 * provider that may be slow or down.
 */
public record CancellationOutcome(
        Kind kind,
        CancellationResponse response,
        RefundInstruction instruction) {

    public enum Kind {

        /** Nothing more to do: the booking was never paid for, or the policy refunds nothing. */
        SETTLED,

        /** Already cancelled by an earlier request; the original outcome is replayed. */
        ALREADY_CANCELLED,

        /** Cancelled and a refund is owed; the gateway must now be called. */
        REFUND_DUE
    }

    /** What the orchestrator must ask the gateway to return. */
    public record RefundInstruction(
            UUID refundId,
            String originalGatewayReference,
            BigDecimal amount,
            String currency) {
    }

    public static CancellationOutcome settled(CancellationResponse response) {
        return new CancellationOutcome(Kind.SETTLED, response, null);
    }

    public static CancellationOutcome alreadyCancelled(CancellationResponse response) {
        return new CancellationOutcome(Kind.ALREADY_CANCELLED, response, null);
    }

    public static CancellationOutcome refundDue(CancellationResponse response, RefundInstruction instruction) {
        return new CancellationOutcome(Kind.REFUND_DUE, response, instruction);
    }
}
