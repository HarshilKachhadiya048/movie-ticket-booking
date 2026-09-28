package com.harshil.movieticketbooking.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harshil.movieticketbooking.payment.config.MockPaymentProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Determinism of the mock gateway. If these outcomes were not reliable, every
 * payment integration test would be flaky for reasons unrelated to the code
 * under test.
 */
class MockPaymentGatewayTest {

    private static final MockPaymentProperties DEFAULT_SUCCESS = new MockPaymentProperties(
            Duration.ZERO, MockPaymentProperties.Outcome.SUCCESS);

    private static PaymentGateway.PaymentRequest chargeWith(String token) {
        return new PaymentGateway.PaymentRequest(
                "idem-key", new BigDecimal("400.00"), "INR", token, "Booking MTB-TEST");
    }

    @Test
    void successTokenAlwaysApproves() {
        MockPaymentGateway gateway = new MockPaymentGateway(DEFAULT_SUCCESS);

        PaymentGateway.PaymentResult result = gateway.charge(
                chargeWith(PaymentMethodToken.SUCCESS.token()));

        assertThat(result.successful()).isTrue();
        assertThat(result.gatewayReference()).startsWith("ch_");
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void failureTokenAlwaysDeclines() {
        MockPaymentGateway gateway = new MockPaymentGateway(DEFAULT_SUCCESS);

        PaymentGateway.PaymentResult result = gateway.charge(
                chargeWith(PaymentMethodToken.FAILURE.token()));

        assertThat(result.successful()).isFalse();
        assertThat(result.gatewayReference()).isNull();
        assertThat(result.failureReason()).isNotBlank();
    }

    /**
     * A transport failure is not a decline. It throws, so the caller can leave
     * the payment PENDING rather than asserting that no money moved.
     */
    @Test
    void errorTokenThrowsRatherThanReturningADecline() {
        MockPaymentGateway gateway = new MockPaymentGateway(DEFAULT_SUCCESS);

        assertThatThrownBy(() -> gateway.charge(chargeWith(PaymentMethodToken.ERROR.token())))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void reservedTokensOverrideTheConfiguredDefault() {
        MockPaymentGateway declineByDefault = new MockPaymentGateway(
                new MockPaymentProperties(Duration.ZERO, MockPaymentProperties.Outcome.FAILURE));

        assertThat(declineByDefault.charge(chargeWith(PaymentMethodToken.SUCCESS.token())).successful())
                .isTrue();
        assertThat(declineByDefault.charge(chargeWith("card_4242")).successful())
                .isFalse();
    }

    @Test
    void unrecognisedTokensFollowTheConfiguredDefault() {
        MockPaymentGateway gateway = new MockPaymentGateway(DEFAULT_SUCCESS);

        assertThat(gateway.charge(chargeWith("card_visa_4242")).successful()).isTrue();
    }

    @Test
    void tokenResolutionIgnoresCaseAndSurroundingWhitespace() {
        assertThat(PaymentMethodToken.resolve("  PM_SUCCESS ")).isEqualTo(PaymentMethodToken.SUCCESS);
        assertThat(PaymentMethodToken.resolve("pm_failure")).isEqualTo(PaymentMethodToken.FAILURE);
        assertThat(PaymentMethodToken.resolve("anything-else")).isNull();
        assertThat(PaymentMethodToken.resolve(null)).isNull();
    }

    @Test
    void refundsReturnAGatewayReference() {
        MockPaymentGateway gateway = new MockPaymentGateway(DEFAULT_SUCCESS);

        PaymentGateway.RefundResult result = gateway.refund(new PaymentGateway.RefundRequest(
                "refund-key", "ch_original", new BigDecimal("400.00"), "INR", "Customer cancelled"));

        assertThat(result.successful()).isTrue();
        assertThat(result.gatewayReference()).startsWith("rf_");
    }
}
