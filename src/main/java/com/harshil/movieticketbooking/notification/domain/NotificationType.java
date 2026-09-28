package com.harshil.movieticketbooking.notification.domain;

/** What a notification is telling the customer about. */
public enum NotificationType {

    BOOKING_CONFIRMED,
    BOOKING_CANCELLED,
    REFUND_COMPLETED,
    PAYMENT_FAILED,

    /** Sent once per booking, ahead of the show. */
    SHOW_REMINDER
}
