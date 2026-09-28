package com.harshil.movieticketbooking.payment.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the mock gateway, bound from {@code payment.mock.*}.
 *
 * @param latency        artificial delay per gateway call. Zero by default so
 *                       tests are fast; set it to a second or two locally to
 *                       demonstrate that a slow gateway does not hold database
 *                       locks or stall other bookings.
 * @param defaultOutcome what an unrecognised payment method token does. The
 *                       reserved tokens in
 *                       {@link com.harshil.movieticketbooking.payment.gateway.PaymentMethodToken}
 *                       always win over this.
 */
@Validated
@ConfigurationProperties(prefix = "payment.mock")
public record MockPaymentProperties(
        @NotNull @DefaultValue("0ms") Duration latency,
        @NotNull @DefaultValue("SUCCESS") Outcome defaultOutcome) {

    public enum Outcome {
        SUCCESS,
        FAILURE
    }
}
