package com.harshil.movieticketbooking.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to pay for a held booking.
 *
 * @param paymentMethodToken the payment instrument. The mock gateway
 *                           recognises {@code pm_success},
 *                           {@code pm_failure} and {@code pm_error}, so every
 *                           outcome is reachable deterministically; any other
 *                           value behaves like an ordinary card.
 * @param idempotencyKey     client-supplied and enforced unique in the
 *                           database. Retrying a request with the same key
 *                           returns the original outcome instead of charging
 *                           again - which matters precisely when the client
 *                           does not know whether the first attempt landed.
 */
public record PayBookingRequest(
        @NotBlank(message = "paymentMethodToken is required")
        @Size(max = 100, message = "paymentMethodToken must not exceed 100 characters")
        String paymentMethodToken,

        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 100, message = "idempotencyKey must not exceed 100 characters")
        String idempotencyKey) {
}
