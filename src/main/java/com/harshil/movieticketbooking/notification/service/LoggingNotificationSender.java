package com.harshil.movieticketbooking.notification.service;

import com.harshil.movieticketbooking.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The mock transport: it logs instead of sending.
 * <p>
 * The transport is a stub; everything around it is real. Notifications are
 * persisted transactionally, dispatched after commit on a bounded executor and
 * deduplicated by key, so swapping in SMTP or SMS means implementing
 * {@link NotificationSender} and nothing else.
 * <p>
 * Swapping in a real transport means providing another
 * {@link NotificationSender} bean marked {@code @Primary}, or deleting this
 * class. Note that {@code @ConditionalOnMissingBean} would be the wrong tool
 * here: it is only evaluated reliably inside auto-configuration, and on a
 * component-scanned bean it can skip registration depending on scan order -
 * which fails at startup rather than falling back.
 */
@Slf4j
@Component
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public void send(Notification notification) {
        log.info("[{}] to {} via {} | {} | {}",
                notification.getType(),
                notification.getRecipient(),
                notification.getChannel(),
                notification.getSubject(),
                notification.getPayload());
    }
}
