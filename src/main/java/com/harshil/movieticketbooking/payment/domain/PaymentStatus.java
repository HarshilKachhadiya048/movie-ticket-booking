package com.harshil.movieticketbooking.payment.domain;

/** Outcome of a single payment attempt. */
public enum PaymentStatus {

    /** Recorded before the gateway is called, so an in-flight charge is durable. */
    PENDING,

    /** Charged. At most one per booking, enforced by a partial unique index. */
    SUCCESS,

    /** Declined or errored. The booking's seats have been released. */
    FAILED;

    public boolean isSettled() {
        return this != PENDING;
    }
}
