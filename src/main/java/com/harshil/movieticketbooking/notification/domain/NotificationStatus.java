package com.harshil.movieticketbooking.notification.domain;

/** Delivery state of a single notification. */
public enum NotificationStatus {

    /**
     * Persisted in the business transaction, not yet delivered. A row sitting
     * in this state is exactly what a durable outbox poller would claim.
     */
    PENDING,

    SENT,

    FAILED
}
