package com.harshil.movieticketbooking.refund.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Refund settings bound from {@code refund.*}.
 * <p>
 * The percentages themselves are deliberately not here: they are rows in
 * {@code refund_policy_rules}, editable through the admin API.
 *
 * @param cancellationCutoffBeforeShow how close to the show a customer may
 *                                     still cancel at all. The default of zero
 *                                     means cancellation is allowed right up
 *                                     to the show start; the applicable policy
 *                                     may still refund 0% in that window.
 */
@Validated
@ConfigurationProperties(prefix = "refund")
public record RefundProperties(
        @NotNull @DefaultValue("0m") Duration cancellationCutoffBeforeShow) {
}
