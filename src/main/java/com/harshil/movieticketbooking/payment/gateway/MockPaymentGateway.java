package com.harshil.movieticketbooking.payment.gateway;

import com.harshil.movieticketbooking.payment.config.MockPaymentProperties;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * A stand-in for a real payment provider.
 * <p>
 * The brief rules out integrating a real gateway, but the surrounding flow has
 * to behave as though one were there, so this implementation deliberately
 * keeps the properties that make the flow hard:
 * <ul>
 *     <li>it can take time ({@code payment.mock.latency}), which is what makes
 *     "never hold a transaction open across a gateway call" a real constraint
 *     rather than a slogan;</li>
 *     <li>it can decline, which must release seats;</li>
 *     <li>it can fail outright with an unknown outcome, which must <em>not</em>
 *     be treated as a decline.</li>
 * </ul>
 * Which of those happens is decided by the request's payment method token, so
 * every path is reachable deterministically from a test or a curl command.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    private static final String CHARGE_REFERENCE_PREFIX = "ch_";
    private static final String REFUND_REFERENCE_PREFIX = "rf_";
    private static final String DECLINE_REASON = "Card declined by issuer";
    private static final String GATEWAY_ERROR_REASON = "Simulated gateway transport failure";

    private final MockPaymentProperties properties;

    @Override
    public PaymentResult charge(PaymentRequest request) {
        simulateLatency();

        PaymentMethodToken token = PaymentMethodToken.resolve(request.methodToken());
        if (token == PaymentMethodToken.ERROR) {
            log.warn("Mock gateway transport failure for idempotencyKey={}", request.idempotencyKey());
            throw new PaymentGatewayException(GATEWAY_ERROR_REASON);
        }

        boolean approved = token == null
                ? properties.defaultOutcome() == MockPaymentProperties.Outcome.SUCCESS
                : token == PaymentMethodToken.SUCCESS;

        if (!approved) {
            log.info("Mock gateway declined charge of {} {} for idempotencyKey={}",
                    request.amount(), request.currency(), request.idempotencyKey());
            return PaymentResult.declined(DECLINE_REASON);
        }

        String reference = CHARGE_REFERENCE_PREFIX + UUID.randomUUID();
        log.info("Mock gateway approved charge of {} {} as {}",
                request.amount(), request.currency(), reference);
        return PaymentResult.approved(reference);
    }

    /**
     * Refunds always succeed here. A real provider can reject a refund, which
     * is why {@code refunds} carries its own status and failure reason: the
     * row stays FAILED for operational follow-up instead of the cancellation
     * being rolled back. The customer's seats are released either way.
     */
    @Override
    public RefundResult refund(RefundRequest request) {
        simulateLatency();

        String reference = REFUND_REFERENCE_PREFIX + UUID.randomUUID();
        log.info("Mock gateway refunded {} {} against {} as {}",
                request.amount(), request.currency(), request.originalGatewayReference(), reference);
        return RefundResult.completed(reference);
    }

    private void simulateLatency() {
        long millis = properties.latency().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException("Interrupted while calling the payment gateway", ex);
        }
    }
}
