package com.harshil.movieticketbooking.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.PayBookingRequest;
import com.harshil.movieticketbooking.booking.service.BookingCancellationService;
import com.harshil.movieticketbooking.booking.service.BookingPaymentService;
import com.harshil.movieticketbooking.booking.service.CreateHoldCommand;
import com.harshil.movieticketbooking.booking.service.SeatHoldService;
import com.harshil.movieticketbooking.notification.domain.Notification;
import com.harshil.movieticketbooking.notification.domain.NotificationStatus;
import com.harshil.movieticketbooking.notification.domain.NotificationType;
import com.harshil.movieticketbooking.notification.repository.NotificationRepository;
import com.harshil.movieticketbooking.notification.service.ShowReminderService;
import com.harshil.movieticketbooking.payment.gateway.PaymentMethodToken;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.Eventually;
import com.harshil.movieticketbooking.support.RecordingNotificationSender;
import com.harshil.movieticketbooking.support.TestScenario;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Asynchronous notifications and reminder idempotency.
 */
@Import(RecordingNotificationSender.class)
class NotificationIT extends AbstractIntegrationTest {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private BookingPaymentService bookingPaymentService;

    @Autowired
    private BookingCancellationService bookingCancellationService;

    @Autowired
    private ShowReminderService showReminderService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RecordingNotificationSender recordingSender;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearRecordedDeliveries() {
        recordingSender.reset();
    }

    @Nested
    @DisplayName("transactional queueing, asynchronous delivery")
    class QueueingAndDelivery {

        /**
         * The row is written inside the booking transaction, so it is already
         * committed by the time the caller gets its response - nothing is lost
         * if delivery never happens.
         */
        @Test
        void aConfirmationIsPersistedBeforeTheCallerGetsAResponse() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse confirmed = holdAndPay(scenario);

            Notification notification = notificationRepository
                    .findByDedupeKey(Notification.dedupeKeyFor(
                            NotificationType.BOOKING_CONFIRMED, confirmed.bookingId()))
                    .orElseThrow();

            assertThat(notification.getRecipient()).contains("@");
            assertThat(notification.getPayload()).contains(confirmed.bookingReference());
            assertThat(notification.getBookingId()).isEqualTo(confirmed.bookingId());
        }

        /**
         * The evidence that the booking request is not blocked by delivery:
         * the send happened on a pool thread, not on the caller's.
         */
        @Test
        @DisplayName("delivery happens on the notification executor, not the calling thread")
        void deliveryRunsOffTheCallingThread() {
            TestScenario scenario = testData.createBookableShow();
            String callerThread = Thread.currentThread().getName();

            holdAndPay(scenario);

            Eventually.assertThat("the confirmation to be delivered", DELIVERY_TIMEOUT,
                    () -> !recordingSender.deliveriesOfType(NotificationType.BOOKING_CONFIRMED).isEmpty());

            RecordingNotificationSender.Delivery delivery =
                    recordingSender.deliveriesOfType(NotificationType.BOOKING_CONFIRMED).getFirst();
            assertThat(delivery.threadName())
                    .as("delivery must not run on the request thread")
                    .isNotEqualTo(callerThread)
                    .startsWith("notification-");
        }

        @Test
        void aDeliveredNotificationIsMarkedSent() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse confirmed = holdAndPay(scenario);
            String dedupeKey = Notification.dedupeKeyFor(
                    NotificationType.BOOKING_CONFIRMED, confirmed.bookingId());

            Eventually.assertThat("the notification to be marked sent", DELIVERY_TIMEOUT,
                    () -> notificationRepository.findByDedupeKey(dedupeKey)
                            .map(notification -> notification.getStatus() == NotificationStatus.SENT)
                            .orElse(false));

            Notification notification = notificationRepository.findByDedupeKey(dedupeKey).orElseThrow();
            assertThat(notification.getSentAt()).isNotNull();
            assertThat(notification.getAttemptCount()).isEqualTo(1);
        }

        @Test
        void cancellingQueuesCancellationAndRefundNotifications() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse confirmed = holdAndPay(scenario);

            bookingCancellationService.cancel(confirmed.bookingId(), scenario.customerId());

            assertThat(notificationTypesFor(confirmed.bookingId()))
                    .contains(
                            NotificationType.BOOKING_CONFIRMED.name(),
                            NotificationType.BOOKING_CANCELLED.name(),
                            NotificationType.REFUND_COMPLETED.name());
        }

        @Test
        void aDeclinedPaymentNotifiesTheCustomer() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario);

            try {
                bookingPaymentService.pay(held.bookingId(), scenario.customerId(),
                        new PayBookingRequest(PaymentMethodToken.FAILURE.token(), "notify-fail"));
            } catch (RuntimeException expected) {
                // The decline itself is asserted in BookingLifecycleIT.
            }

            assertThat(notificationTypesFor(held.bookingId()))
                    .contains(NotificationType.PAYMENT_FAILED.name());
        }
    }

    @Nested
    @DisplayName("show reminders")
    class Reminders {

        @Test
        void aBookingInsideTheLeadTimeGetsOneReminder() {
            TestScenario scenario = testData.createBookableShow(
                    Instant.now(clock).plus(Duration.ofMinutes(90)));
            BookingResponse confirmed = holdAndPay(scenario);

            int queued = showReminderService.processDueReminders();

            assertThat(queued).isEqualTo(1);
            assertThat(notificationTypesFor(confirmed.bookingId()))
                    .contains(NotificationType.SHOW_REMINDER.name());
        }

        @Test
        void aBookingOutsideTheLeadTimeIsNotRemindedYet() {
            TestScenario scenario = testData.createBookableShow(
                    Instant.now(clock).plus(Duration.ofHours(48)));
            holdAndPay(scenario);

            assertThat(showReminderService.processDueReminders()).isZero();
        }

        /**
         * The idempotency requirement. A scheduler that ran late, ran twice,
         * or ran on two instances at once must still produce exactly one
         * reminder.
         */
        @Test
        @DisplayName("running the sweep repeatedly still sends exactly one reminder")
        void remindersAreIdempotent() {
            TestScenario scenario = testData.createBookableShow(
                    Instant.now(clock).plus(Duration.ofMinutes(90)));
            BookingResponse confirmed = holdAndPay(scenario);

            assertThat(showReminderService.processDueReminders()).isEqualTo(1);
            assertThat(showReminderService.processDueReminders()).isZero();
            assertThat(showReminderService.processDueReminders()).isZero();

            assertThat(reminderCountFor(confirmed.bookingId())).isEqualTo(1);
        }

        /** The database-level guarantee behind that idempotency. */
        @Test
        void theDedupeKeyIsUniqueInTheDatabase() {
            TestScenario scenario = testData.createBookableShow(
                    Instant.now(clock).plus(Duration.ofMinutes(90)));
            BookingResponse confirmed = holdAndPay(scenario);
            showReminderService.processDueReminders();

            String dedupeKey = Notification.dedupeKeyFor(
                    NotificationType.SHOW_REMINDER, confirmed.bookingId());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
                            INSERT INTO notifications (id, user_id, booking_id, type, channel, recipient, subject,
                                                       payload, status, dedupe_key, attempt_count, available_at,
                                                       version, created_at, updated_at)
                            VALUES (gen_random_uuid(), ?, ?, 'SHOW_REMINDER', 'EMAIL', 'x@example.test', 'dupe',
                                    '{}', 'PENDING', ?, 0, now(), 0, now(), now())
                            """, scenario.customerId(), confirmed.bookingId(), dedupeKey))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        @Test
        void unconfirmedBookingsAreNeverReminded() {
            TestScenario scenario = testData.createBookableShow(
                    Instant.now(clock).plus(Duration.ofMinutes(90)));
            hold(scenario);

            assertThat(showReminderService.processDueReminders()).isZero();
        }

        @Test
        void cancelledBookingsAreNeverReminded() {
            TestScenario scenario = testData.createBookableShow(
                    Instant.now(clock).plus(Duration.ofMinutes(90)));
            BookingResponse confirmed = holdAndPay(scenario);
            bookingCancellationService.cancel(confirmed.bookingId(), scenario.customerId());

            assertThat(showReminderService.processDueReminders()).isZero();
        }
    }

    // -----------------------------------------------------------------------

    private BookingResponse hold(TestScenario scenario) {
        return seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), scenario.premiumSeatIds(), null, scenario.customerId()));
    }

    private BookingResponse holdAndPay(TestScenario scenario) {
        BookingResponse held = hold(scenario);
        return bookingPaymentService.pay(held.bookingId(), scenario.customerId(),
                new PayBookingRequest(PaymentMethodToken.SUCCESS.token(), "notify-" + UUID.randomUUID()));
    }

    private java.util.List<String> notificationTypesFor(UUID bookingId) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM notifications WHERE booking_id = ?", String.class, bookingId);
    }

    private long reminderCountFor(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE booking_id = ? AND type = 'SHOW_REMINDER'",
                Long.class, bookingId);
    }
}
