package com.harshil.movieticketbooking.refund.domain;

/** Progress of a refund through the gateway. */
public enum RefundStatus {

    /** Recorded and committed before the gateway is called. */
    PENDING,

    /** Money returned. */
    COMPLETED,

    /**
     * The gateway rejected the refund. The booking is still cancelled and the
     * seats are still released - the customer is not held hostage to a
     * provider outage. The row stays FAILED for operational follow-up.
     */
    FAILED
}
