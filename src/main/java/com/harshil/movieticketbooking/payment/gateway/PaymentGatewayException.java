package com.harshil.movieticketbooking.payment.gateway;

/**
 * The gateway could not be reached or returned something unusable.
 * <p>
 * Deliberately distinct from a decline. A decline is a definite "no" and the
 * booking can be failed and its seats released with confidence. This exception
 * means the outcome is <em>unknown</em> - the charge may or may not have gone
 * through - so the caller leaves the payment row PENDING for reconciliation
 * rather than assuming either way, and reports 502.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
