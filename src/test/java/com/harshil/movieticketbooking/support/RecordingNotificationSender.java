package com.harshil.movieticketbooking.support;

import com.harshil.movieticketbooking.notification.domain.Notification;
import com.harshil.movieticketbooking.notification.domain.NotificationType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * A {@link com.harshil.movieticketbooking.notification.service.NotificationSender}
 * that records what it was asked to deliver, and - crucially - which thread
 * asked.
 * <p>
 * The thread name is the evidence for "notification delivery does not block
 * the booking request". Asserting that a row eventually reaches SENT only
 * proves it was delivered; asserting that it was delivered on a
 * {@code notification-} thread proves it was not delivered on the caller's.
 */
@Primary
@TestComponent
public class RecordingNotificationSender
        implements com.harshil.movieticketbooking.notification.service.NotificationSender {

    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

    @Override
    public void send(Notification notification) {
        deliveries.add(new Delivery(
                notification.getType(),
                notification.getDedupeKey(),
                notification.getRecipient(),
                Thread.currentThread().getName()));
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    public List<Delivery> deliveriesOfType(NotificationType type) {
        return deliveries.stream().filter(delivery -> delivery.type() == type).toList();
    }

    public void reset() {
        deliveries.clear();
    }

    public record Delivery(NotificationType type, String dedupeKey, String recipient, String threadName) {
    }
}
