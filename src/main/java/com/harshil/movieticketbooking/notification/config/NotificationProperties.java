package com.harshil.movieticketbooking.notification.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Notification delivery and reminder settings, bound from
 * {@code notification.*}.
 *
 * @param enabled  master switch; when off, notifications are still persisted
 *                 but never dispatched, which is useful for load testing
 * @param executor the bounded pool described in
 *                 {@link com.harshil.movieticketbooking.common.config.AsyncConfig}
 * @param reminder the pre-show reminder sweep
 */
@Validated
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue Executor executor,
        @NotNull @DefaultValue Reminder reminder) {

    public record Executor(
            @Min(1) @DefaultValue("4") int corePoolSize,
            @Min(1) @DefaultValue("8") int maxPoolSize,
            @Min(1) @DefaultValue("1000") int queueCapacity,
            @NotBlank @DefaultValue("notification-") String threadNamePrefix,
            @NotNull @DefaultValue("PT20S") Duration awaitTermination) {
    }

    /**
     * @param leadTime how far ahead of the show a reminder is sent. A booking
     *                 becomes eligible once the show starts within this window.
     */
    public record Reminder(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("PT60S") Duration interval,
            @NotNull @DefaultValue("2h") Duration leadTime,
            @Min(1) @DefaultValue("100") int batchSize) {
    }
}
