package com.harshil.movieticketbooking.notification.event;

import java.util.UUID;

/**
 * Published when a notification row has been written inside a business
 * transaction and is waiting to be delivered.
 * <p>
 * The event carries only the id, not the notification itself. That is
 * deliberate: it is consumed after the publishing transaction has committed,
 * on a different thread, where a detached entity would be a liability. The
 * consumer re-reads the row in its own transaction, so it always works with
 * committed state.
 *
 * @param notificationId the persisted, committed notification
 */
public record NotificationQueuedEvent(UUID notificationId) {
}
