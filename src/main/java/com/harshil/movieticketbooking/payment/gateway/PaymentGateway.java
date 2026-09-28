package com.harshil.movieticketbooking.payment.gateway;

import java.math.BigDecimal;

/**
 * The boundary between this application and whatever actually moves money.
 * <p>
 * Everything behind this interface is treated as a remote call that can be
 * slow, can fail, and - crucially - must never be made while a database
 * transaction is open. The booking flow is deliberately split into
 * "commit the hold", "call the gateway", "commit the outcome" so that a slow
 * gateway holds no row locks. See
 * {@link com.harshil.movieticketbooking.booking.service.BookingPaymentService}.
 * <p>
 * Implementations should be side-effect free with respect to this
 * application's database: they return a result, and the caller persists it.
 */
public interface PaymentGateway {

    /**
     * Attempts to charge {@code amount}.
     *
     * @param request what to charge, against which method, under which
     *                idempotency key
     * @return the outcome; a decline is a normal return value, not an exception
     * @throws PaymentGatewayException if the gateway itself could not be
     *                                 reached or misbehaved. Distinct from a
     *                                 decline: the caller cannot assume the
     *                                 charge did not happen.
     */
    PaymentResult charge(PaymentRequest request);

    /**
     * Returns money for a previously successful charge.
     *
     * @throws PaymentGatewayException if the gateway could not be reached
     */
    RefundResult refund(RefundRequest request);

    /** @param amount always a scale-2 {@link BigDecimal}; never a float */
    record PaymentRequest(
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            String methodToken,
            String description) {
    }

    record PaymentResult(
            boolean successful,
            String gatewayReference,
            String failureReason) {

        public static PaymentResult approved(String gatewayReference) {
            return new PaymentResult(true, gatewayReference, null);
        }

        public static PaymentResult declined(String failureReason) {
            return new PaymentResult(false, null, failureReason);
        }
    }

    record RefundRequest(
            String idempotencyKey,
            String originalGatewayReference,
            BigDecimal amount,
            String currency,
            String reason) {
    }

    record RefundResult(
            boolean successful,
            String gatewayReference,
            String failureReason) {

        public static RefundResult completed(String gatewayReference) {
            return new RefundResult(true, gatewayReference, null);
        }

        public static RefundResult rejected(String failureReason) {
            return new RefundResult(false, null, failureReason);
        }
    }
}
