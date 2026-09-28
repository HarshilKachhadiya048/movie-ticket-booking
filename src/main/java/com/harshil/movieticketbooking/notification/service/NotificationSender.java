package com.harshil.movieticketbooking.notification.service;

import com.harshil.movieticketbooking.notification.domain.Notification;

/**
 * The transport that actually delivers a notification.
 * <p>
 * Kept as an interface so the mock implementation used here can be swapped for
 * a real email or SMS provider without touching the booking flow, the
 * persistence of notifications or the executor wiring. A real implementation
 * would be the only thing that changes.
 */
public interface NotificationSender {

    /**
     * Delivers the notification.
     *
     * @throws RuntimeException if delivery fails; the caller records the
     *                          failure on the notification row rather than
     *                          letting it escape
     */
    void send(Notification notification);
}
