package com.harshil.movieticketbooking.notification.service;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.repository.BookingRepository;
import com.harshil.movieticketbooking.notification.config.NotificationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends each confirmed booking one reminder before its show.
 * <p>
 * <b>Idempotent three times over</b>, which is what a scheduler that may run
 * late, twice, or on two instances at once requires:
 * <ol>
 *     <li>the claim query only selects bookings with
 *     {@code reminder_sent_at IS NULL}, and marks them in the same
 *     transaction;</li>
 *     <li>the claim takes {@code FOR UPDATE ... SKIP LOCKED}, so a second
 *     instance running concurrently takes a different batch rather than
 *     duplicating this one;</li>
 *     <li>the notification itself carries the dedupe key
 *     {@code SHOW_REMINDER:<bookingId>} under a unique index, so even if the
 *     first two were somehow bypassed only one row could exist.</li>
 * </ol>
 * Layer three is the one that actually guarantees it; the first two just stop
 * the work being done twice.
 * <p>
 * Kept separate from the scheduler that drives it so tests can call it
 * directly rather than waiting for a tick.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowReminderService {

    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;
    private final NotificationProperties properties;
    private final Clock clock;

    /**
     * Processes one batch of due reminders.
     *
     * @return how many reminders were queued
     */
    @Transactional
    public int processDueReminders() {
        Instant now = Instant.now(clock);
        Instant threshold = now.plus(properties.reminder().leadTime());

        List<UUID> bookingIds = bookingRepository.claimBookingsDueForReminder(
                now, threshold, properties.reminder().batchSize());
        if (bookingIds.isEmpty()) {
            return 0;
        }

        int queued = 0;
        for (UUID bookingId : bookingIds) {
            Booking booking = bookingRepository.findDetailById(bookingId).orElse(null);
            if (booking == null || booking.getReminderSentAt() != null) {
                continue;
            }
            notificationService.notifyShowReminder(booking);
            booking.markReminderSent(now);
            queued++;
        }

        log.info("Queued {} show reminder(s) for shows starting before {}", queued, threshold);
        return queued;
    }
}
