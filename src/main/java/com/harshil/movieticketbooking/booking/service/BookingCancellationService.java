package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.dto.CancellationResponse;
import com.harshil.movieticketbooking.payment.gateway.PaymentGateway;
import com.harshil.movieticketbooking.payment.gateway.PaymentGatewayException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates cancelling a booking.
 * <p>
 * Like the payment flow, and for the same reason, this class holds no
 * transaction: the gateway refund happens between two committed transactions
 * rather than inside one.
 * <pre>
 *   transaction 1   lock booking, assess the policy, write the refund row,
 *                   release seats, cancel the booking, COMMIT
 *   (no transaction)  ask the gateway for the money back
 *   transaction 2   record the gateway's answer, COMMIT
 * </pre>
 * The customer's cancellation is therefore complete and their seats are back
 * on sale before the provider is ever contacted. If the refund is rejected the
 * row is left FAILED for follow-up; the alternative - unwinding the
 * cancellation - would tell a customer who asked to cancel that they are still
 * booked because a third party had an outage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingCancellationService {

    private final BookingCancellationTransactionService transactionService;
    private final PaymentGateway paymentGateway;

    public CancellationResponse cancel(UUID bookingId, UUID userId) {
        CancellationOutcome outcome = transactionService.cancel(bookingId, userId);

        if (outcome.kind() != CancellationOutcome.Kind.REFUND_DUE) {
            return outcome.response();
        }

        CancellationOutcome.RefundInstruction instruction = outcome.instruction();
        PaymentGateway.RefundResult result;
        try {
            result = paymentGateway.refund(new PaymentGateway.RefundRequest(
                    "refund-" + instruction.refundId(),
                    instruction.originalGatewayReference(),
                    instruction.amount(),
                    instruction.currency(),
                    "Booking cancelled by customer"));
        } catch (PaymentGatewayException ex) {
            // The cancellation itself already committed. Record the failure so
            // it can be retried operationally rather than losing it.
            log.error("Refund {} could not be submitted to the gateway", instruction.refundId(), ex);
            result = PaymentGateway.RefundResult.rejected(ex.getMessage());
        }

        return new CancellationResponse(
                outcome.response().booking(),
                transactionService.completeRefund(instruction.refundId(), result));
    }
}
