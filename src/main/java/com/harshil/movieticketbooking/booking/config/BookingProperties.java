package com.harshil.movieticketbooking.booking.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Booking and seat-hold tuning, bound from the {@code booking.*} properties.
 *
 * @param holdDuration       how long a seat hold survives without payment.
 *                           Written onto {@code show_seats.hold_expires_at}
 *                           and re-evaluated under the row lock on every
 *                           subsequent hold attempt.
 * @param currency           ISO 4217 code all amounts are denominated in.
 * @param maxSeatsPerBooking upper bound on seats in one request; caps how many
 *                           rows a single transaction locks.
 * @param referencePrefix    prefix for customer-facing booking references.
 * @param holdSweeper        background cleanup of expired holds.
 */
@Validated
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(
        @NotNull @DefaultValue("5m") Duration holdDuration,
        @NotBlank @DefaultValue("INR") String currency,
        @Min(1) @DefaultValue("10") int maxSeatsPerBooking,
        @NotBlank @DefaultValue("MTB") String referencePrefix,
        @NotNull @DefaultValue HoldSweeper holdSweeper) {

    /**
     * The sweeper is a tidiness mechanism, not a correctness one. Seats held by
     * an expired hold are already treated as available by the hold transaction
     * itself; the sweep exists so that seat maps and reporting do not show
     * stale HELD rows, and so abandoned bookings reach a terminal state.
     * Disabling it cannot cause a seat to be permanently lost.
     */
    public record HoldSweeper(
            @DefaultValue("true") boolean enabled,
            @NotNull @DefaultValue("PT30S") Duration interval,
            @Min(1) @DefaultValue("200") int batchSize) {
    }
}
