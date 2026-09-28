package com.harshil.movieticketbooking.notification.scheduler;

import com.harshil.movieticketbooking.notification.service.ShowReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link ShowReminderService} on a fixed delay.
 * <p>
 * A thin trigger with no logic of its own. {@code fixedDelay} rather than
 * {@code fixedRate} so a slow batch cannot cause runs to pile up on top of
 * each other.
 * <p>
 * Disabling this via {@code notification.reminder.enabled=false} - as the test
 * profile does - stops reminders being sent, but cannot cause duplicates or
 * corrupt anything: the service it calls is idempotent regardless of how often
 * or how late it runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notification.reminder.enabled", havingValue = "true", matchIfMissing = true)
public class ShowReminderScheduler {

    private final ShowReminderService showReminderService;

    @Scheduled(
            fixedDelayString = "${notification.reminder.interval:PT60S}",
            initialDelayString = "${notification.reminder.interval:PT60S}")
    public void sendDueReminders() {
        try {
            showReminderService.processDueReminders();
        } catch (RuntimeException ex) {
            // Never let a failed sweep kill the scheduled task registration.
            log.error("Show reminder sweep failed; will retry on the next tick", ex);
        }
    }
}
