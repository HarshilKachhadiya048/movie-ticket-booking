package com.harshil.movieticketbooking.notification.service;

import com.harshil.movieticketbooking.common.config.AsyncConfig;
import com.harshil.movieticketbooking.notification.event.NotificationQueuedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Where a booking stops waiting and the notification starts travelling.
 * <p>
 * <b>{@code AFTER_COMMIT}</b> means the send is only attempted once the
 * booking is durably committed. A plain {@code @EventListener} would fire
 * inside the transaction and could email a customer about a booking that then
 * rolled back.
 * <p>
 * <b>{@code @Async} on the dedicated executor</b> means the HTTP thread hands
 * the work off and returns. The booking response does not wait for delivery,
 * and a slow or broken notification transport cannot add latency to - or fail
 * - a booking that has already succeeded.
 * <p>
 * Nothing is allowed to escape this method. By the time it runs the response
 * may already have been written, so an exception here would have nowhere
 * useful to go; it is logged instead, and the row's own status carries the
 * outcome.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final NotificationDispatcher notificationDispatcher;

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationQueued(NotificationQueuedEvent event) {
        try {
            notificationDispatcher.dispatch(event.notificationId());
        } catch (RuntimeException ex) {
            log.error("Notification dispatch failed for {}", event.notificationId(), ex);
        }
    }
}
