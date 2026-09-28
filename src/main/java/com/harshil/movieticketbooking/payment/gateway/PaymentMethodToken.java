package com.harshil.movieticketbooking.payment.gateway;

import java.util.Locale;

/**
 * Reserved payment method tokens understood by {@link MockPaymentGateway}.
 * <p>
 * Modelled on how real providers ship test tokens: the outcome travels with
 * the request rather than living in configuration. That is what makes it
 * possible for one integration test to drive a successful payment and the next
 * to drive a decline, in the same application context, with no property
 * juggling, no mocking of the gateway bean and no shared mutable state between
 * tests.
 * <p>
 * Any token outside this set is treated as an ordinary card and falls through
 * to the configured default outcome.
 */
public enum PaymentMethodToken {

    /** Always approved. */
    SUCCESS("pm_success"),

    /** Always declined - a normal, definite "no" from the issuer. */
    FAILURE("pm_failure"),

    /** Always throws {@link PaymentGatewayException} - outcome unknown. */
    ERROR("pm_error");

    private final String token;

    PaymentMethodToken(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    /** Returns {@code null} for any token that is not one of the reserved ones. */
    public static PaymentMethodToken resolve(String candidate) {
        if (candidate == null) {
            return null;
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        for (PaymentMethodToken value : values()) {
            if (value.token.equals(normalized)) {
                return value;
            }
        }
        return null;
    }
}
