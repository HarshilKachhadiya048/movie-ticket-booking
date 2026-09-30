package com.harshil.movieticketbooking.notification.service;

import com.harshil.movieticketbooking.notification.config.NotificationProperties;
import com.harshil.movieticketbooking.notification.domain.Notification;
import com.harshil.movieticketbooking.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers one queued notification and records the outcome.
 * <p>
 * Runs in its own short transaction, on a notification thread, after the
 * business transaction that queued the row has already committed. It reads the
 * row rather than receiving an entity, so it always works from committed state
 * and never touches a detached object.
 * <p>
 * A delivery failure is caught and written to the row as FAILED. Letting it
 * escape would achieve nothing - the booking is long since committed and there
 * is no caller left to tell - whereas recording it leaves a durable trace.
 * <p>
 * This class is the seam for durable delivery. An outbox poller claiming
 * PENDING rows with {@code FOR UPDATE SKIP LOCKED} would call this exact
 * method; nothing above it would change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;
    private final NotificationProperties properties;
    private final Clock clock;

    @Transactional
    public void dispatch(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            log.warn("Notification {} disappeared before dispatch", notificationId);
            return;
        }
        if (!notification.isPending()) {
            log.debug("Notification {} already {}, skipping", notificationId, notification.getStatus());
            return;
        }
        if (!properties.enabled()) {
            // Left PENDING on purpose, so nothing is lost while delivery is off.
            log.debug("Notifications disabled; leaving {} pending", notification.getDedupeKey());
            return;
        }

        try {
            notificationSender.send(notification);
            notification.markSent(Instant.now(clock));
        } catch (RuntimeException ex) {
            log.error("Failed to deliver notification {}", notification.getDedupeKey(), ex);
            notification.markFailed(ex.getMessage());
        }
    }
}
