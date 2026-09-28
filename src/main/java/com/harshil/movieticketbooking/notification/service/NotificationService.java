package com.harshil.movieticketbooking.notification.service;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.notification.domain.Notification;
import com.harshil.movieticketbooking.notification.domain.NotificationChannel;
import com.harshil.movieticketbooking.notification.domain.NotificationStatus;
import com.harshil.movieticketbooking.notification.domain.NotificationType;
import com.harshil.movieticketbooking.notification.event.NotificationQueuedEvent;
import com.harshil.movieticketbooking.notification.repository.NotificationRepository;
import com.harshil.movieticketbooking.payment.domain.Payment;
import com.harshil.movieticketbooking.refund.domain.Refund;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Queues notifications for delivery.
 * <p>
 * <b>The row is written inside the business transaction; delivery happens
 * after it commits.</b> "The booking is confirmed" and "a confirmation is
 * owed" therefore commit or roll back together - a rolled-back booking cannot
 * leave a confirmation behind, and a committed booking cannot silently lose
 * one. The actual send is triggered by an after-commit event on a bounded
 * executor, so the customer's HTTP request returns without waiting for it.
 * <p>
 * <b>Every method is {@link Propagation#MANDATORY}</b>, which makes that
 * contract impossible to violate by accident: calling one outside a
 * transaction fails immediately rather than quietly writing a notification
 * that is not tied to anything.
 * <p>
 * <b>Deduplication.</b> Each notification has a natural key -
 * {@code BOOKING_CONFIRMED:<bookingId>} - which is checked here and enforced
 * by a unique index. The check is what makes repeat calls a no-op; the index
 * is the backstop. If the index ever did fire it would roll the caller back,
 * and that is the right outcome: it would mean two transactions each believed
 * they had confirmed the same booking, and they must not both commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final NotificationChannel DEFAULT_CHANNEL = NotificationChannel.EMAIL;

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyBookingConfirmed(Booking booking) {
        enqueue(
                booking,
                NotificationType.BOOKING_CONFIRMED,
                booking.getId(),
                "Booking %s confirmed".formatted(booking.getBookingReference()),
                NotificationPayload.forBooking(
                        booking,
                        "Your seats are confirmed. Please arrive 15 minutes before the show."));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyBookingCancelled(Booking booking) {
        enqueue(
                booking,
                NotificationType.BOOKING_CANCELLED,
                booking.getId(),
                "Booking %s cancelled".formatted(booking.getBookingReference()),
                NotificationPayload.forBooking(booking, "Your booking has been cancelled."));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyRefundCompleted(Booking booking, Refund refund) {
        BigDecimal amount = refund.getRefundAmount();
        enqueue(
                booking,
                NotificationType.REFUND_COMPLETED,
                booking.getId(),
                "Refund issued for booking %s".formatted(booking.getBookingReference()),
                NotificationPayload.forRefund(
                        booking,
                        amount,
                        "A refund of %s %s (%s%% under the applicable policy) is on its way.".formatted(
                                booking.getCurrency(), amount, refund.getRefundPercentage())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyPaymentFailed(Booking booking, Payment payment) {
        enqueue(
                booking,
                NotificationType.PAYMENT_FAILED,
                payment.getId(),
                "Payment failed for booking %s".formatted(booking.getBookingReference()),
                NotificationPayload.forBooking(
                        booking,
                        "The payment was declined and the seats have been released. "
                                + "Please start a new booking to try again."));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyShowReminder(Booking booking) {
        enqueue(
                booking,
                NotificationType.SHOW_REMINDER,
                booking.getId(),
                "Reminder: %s starts soon".formatted(booking.getShow().getMovie().getTitle()),
                NotificationPayload.forBooking(booking, "Your show is starting soon."));
    }

    /**
     * Persists the notification and schedules its delivery for after commit.
     *
     * @param aggregateId the entity the dedupe key is built from - usually the
     *                    booking, but the payment for a failed charge
     */
    private void enqueue(
            Booking booking,
            NotificationType type,
            UUID aggregateId,
            String subject,
            NotificationPayload payload) {
        String dedupeKey = Notification.dedupeKeyFor(type, aggregateId);
        if (notificationRepository.existsByDedupeKey(dedupeKey)) {
            log.debug("Notification {} already queued, skipping", dedupeKey);
            return;
        }

        Instant now = Instant.now(clock);
        Notification notification = notificationRepository.save(Notification.builder()
                .userId(booking.getUser().getId())
                .bookingId(booking.getId())
                .type(type)
                .channel(DEFAULT_CHANNEL)
                .recipient(booking.getUser().getEmail())
                .subject(subject)
                .payload(objectMapper.writeValueAsString(payload))
                .status(NotificationStatus.PENDING)
                .dedupeKey(dedupeKey)
                .attemptCount(0)
                .availableAt(now)
                .build());

        // Delivered by NotificationEventListener once this transaction commits.
        eventPublisher.publishEvent(new NotificationQueuedEvent(notification.getId()));
        log.debug("Queued notification {} for booking {}", dedupeKey, booking.getBookingReference());
    }
}
