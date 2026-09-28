package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.PayBookingRequest;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.payment.gateway.PaymentGateway;
import com.harshil.movieticketbooking.payment.gateway.PaymentGatewayException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates payment for a held booking.
 *
 * <h2>Why this class has no {@code @Transactional} anywhere on it</h2>
 * That is the whole point of it. The flow is:
 * <pre>
 *   transaction 1   lock booking, check idempotency and hold, write PENDING payment, COMMIT
 *   (no transaction)  call the payment gateway
 *   transaction 2   lock booking and seats, re-verify the hold, apply the outcome, COMMIT
 * </pre>
 * A gateway call can take seconds and can hang. Holding a database
 * transaction - and therefore row locks on {@code show_seats} - across it
 * would block every other customer trying to book adjacent seats, and would
 * tie up a connection from a finite pool for the duration of somebody else's
 * outage. So the network call happens with nothing held.
 * <p>
 * The cost of that split is a real window: time passes between the two
 * transactions, and during it the hold can lapse. Transaction 2 therefore
 * re-verifies the seat rows under lock rather than trusting transaction 1, and
 * the case where the charge succeeded but the seats were gone is handled
 * explicitly below by refunding the customer - not swept under the rug.
 *
 * <h2>Idempotency</h2>
 * Every request carries a client-supplied key, unique in the database. A
 * retried request - the usual case being a client that timed out and does not
 * know whether the first attempt landed - replays the original outcome instead
 * of charging a second time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingPaymentService {

    private final BookingPaymentTransactionService transactionService;
    private final PaymentGateway paymentGateway;

    public BookingResponse pay(UUID bookingId, UUID userId, PayBookingRequest request) {
        PaymentPreparation preparation = transactionService.prepare(bookingId, userId, request);

        switch (preparation.kind()) {
            case ALREADY_SUCCEEDED -> {
                log.debug("Replaying successful payment for idempotency key {}", request.idempotencyKey());
                return preparation.settledBooking();
            }
            case ALREADY_FAILED -> throw new DomainException(
                    ErrorCode.PAYMENT_FAILED, preparation.failureReason());
            // The booking has already been expired and its seats released, and
            // that committed. Only now is it safe to report the failure.
            case HOLD_EXPIRED -> throw new DomainException(
                    ErrorCode.HOLD_EXPIRED, preparation.failureReason());
            case PROCEED -> {
                // Fall through to the gateway call below.
            }
        }

        PaymentGateway.PaymentResult result = charge(preparation);
        PaymentSettlement settlement = transactionService.settle(bookingId, preparation.paymentId(), result);

        switch (settlement.kind()) {
            case CONFIRMED -> {
                return settlement.booking();
            }
            case DECLINED -> throw new DomainException(
                    ErrorCode.PAYMENT_FAILED, settlement.failureReason());
            case HOLD_LAPSED_AFTER_CHARGE -> {
                compensate(bookingId, settlement.compensation());
                throw new DomainException(
                        ErrorCode.HOLD_EXPIRED,
                        "The seat hold expired while the payment was being processed. "
                                + "The charge has been refunded.");
            }
        }
        throw new IllegalStateException("Unhandled settlement kind " + settlement.kind());
    }

    /**
     * The one call made outside any transaction.
     * <p>
     * A decline comes back as a result and is settled normally. A transport
     * failure is different: the charge may or may not have happened, so the
     * payment is deliberately left PENDING for reconciliation rather than
     * being asserted as failed.
     */
    private PaymentGateway.PaymentResult charge(PaymentPreparation preparation) {
        try {
            return paymentGateway.charge(new PaymentGateway.PaymentRequest(
                    preparation.idempotencyKey(),
                    preparation.amount(),
                    preparation.currency(),
                    preparation.methodToken(),
                    preparation.description()));
        } catch (PaymentGatewayException ex) {
            transactionService.recordGatewayUnavailable(preparation.paymentId(), ex.getMessage());
            throw new DomainException(
                    ErrorCode.PAYMENT_GATEWAY_ERROR,
                    "The payment could not be completed. If you were charged it will be reconciled automatically.");
        }
    }

    /**
     * Hands back a charge whose seats were lost to a lapsed hold.
     * <p>
     * Also outside a transaction, for the same reason the charge was. The
     * result is then persisted as an ordinary {@code refunds} row, so if the
     * gateway rejects the refund it is visible and actionable rather than
     * lost.
     */
    private void compensate(UUID bookingId, PaymentSettlement.Compensation compensation) {
        PaymentGateway.RefundResult refundResult;
        try {
            refundResult = paymentGateway.refund(new PaymentGateway.RefundRequest(
                    "compensate-" + compensation.paymentId(),
                    compensation.gatewayReference(),
                    compensation.amount(),
                    compensation.currency(),
                    "Seat hold expired during payment"));
        } catch (PaymentGatewayException ex) {
            log.error("Compensating refund for payment {} could not be submitted",
                    compensation.paymentId(), ex);
            refundResult = PaymentGateway.RefundResult.rejected(ex.getMessage());
        }

        transactionService.recordCompensatingRefund(
                bookingId, compensation.paymentId(), refundResult, compensation.amount());
    }
}
