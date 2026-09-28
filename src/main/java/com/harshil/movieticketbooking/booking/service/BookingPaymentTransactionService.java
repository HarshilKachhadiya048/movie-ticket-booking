package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.PayBookingRequest;
import com.harshil.movieticketbooking.booking.repository.BookingRepository;
import com.harshil.movieticketbooking.booking.repository.BookingSeatRepository;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.discount.service.DiscountService;
import com.harshil.movieticketbooking.notification.service.NotificationService;
import com.harshil.movieticketbooking.payment.domain.Payment;
import com.harshil.movieticketbooking.payment.gateway.PaymentGateway;
import com.harshil.movieticketbooking.payment.repository.PaymentRepository;
import com.harshil.movieticketbooking.refund.domain.Refund;
import com.harshil.movieticketbooking.refund.domain.RefundStatus;
import com.harshil.movieticketbooking.refund.repository.RefundRepository;
import com.harshil.movieticketbooking.refund.service.RefundCalculator;
import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.repository.ShowSeatRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of the payment flow: the two transactions that bracket the
 * gateway call.
 * <p>
 * <b>Why this is a separate bean from {@link BookingPaymentService}.</b> The
 * two transactions must be genuinely separate, with the network call between
 * them and no lock held across it. Spring's {@code @Transactional} works
 * through a proxy, so a method calling another method on {@code this} bypasses
 * it entirely - putting the orchestration and the transactional steps in one
 * class would silently produce one long transaction spanning the gateway call,
 * which is exactly the failure mode the brief warns about. Splitting the beans
 * makes the boundary real and impossible to lose by accident.
 * <p>
 * <b>Why business failures are returned rather than thrown.</b> "The payment
 * was declined, the seats are released, the booking is terminal" is a state
 * that must be <em>persisted</em>. Throwing from inside the transaction that
 * records it would roll it back and leave the booking stuck in
 * PAYMENT_PENDING, still holding seats it will never pay for. So these methods
 * commit the truth and report it; the orchestrator raises the HTTP error
 * afterwards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingPaymentTransactionService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final ShowSeatRepository showSeatRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final DiscountService discountService;
    private final NotificationService notificationService;
    private final RefundCalculator refundCalculator;
    private final Clock clock;

    // -----------------------------------------------------------------------
    // Transaction 1: validate, record the attempt, commit.
    // -----------------------------------------------------------------------

    /**
     * Locks the booking, applies idempotency, and records a PENDING payment.
     * <p>
     * Committing the PENDING row <em>before</em> the gateway is called is what
     * makes an in-flight charge durable: if the process dies mid-call there is
     * still a record that an attempt was made, rather than a silent gap.
     */
    @Transactional
    public PaymentPreparation prepare(UUID bookingId, UUID userId, PayBookingRequest request) {
        Instant now = Instant.now(clock);

        // lockById is the first load of this row in the transaction, so the
        // state read here is fresh and stays stable until commit.
        Booking booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.BOOKING_NOT_FOUND, "Booking %s does not exist".formatted(bookingId)));
        requireOwnership(booking, userId);

        Optional<PaymentPreparation> replay = replayIfKnownIdempotencyKey(booking, request.idempotencyKey());
        if (replay.isPresent()) {
            return replay.get();
        }

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            throw new DomainException(
                    ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "Booking %s is already confirmed".formatted(booking.getBookingReference()));
        }
        if (booking.getStatus() != BookingStatus.HOLD_CREATED) {
            throw new DomainException(
                    ErrorCode.INVALID_BOOKING_STATE_TRANSITION,
                    "Booking %s is %s and cannot be paid for".formatted(
                            booking.getBookingReference(), booking.getStatus()));
        }
        if (booking.isHoldExpired(now)) {
            // Release here rather than waiting for the sweeper: the customer is
            // in front of us and deserves a definitive answer now. Returned
            // rather than thrown so that the release commits - throwing would
            // roll back the very cleanup this just did.
            String reason = "The hold on booking %s expired at %s".formatted(
                    booking.getBookingReference(), booking.getHoldExpiresAt());
            expireBookingAndReleaseSeats(booking);
            return PaymentPreparation.holdExpired(reason);
        }

        Payment payment = paymentRepository.saveAndFlush(Payment.pending(
                booking,
                request.idempotencyKey(),
                booking.getTotalAmount(),
                booking.getCurrency(),
                request.paymentMethodToken()));
        booking.markPaymentPending();

        log.debug("Prepared payment {} for booking {}", payment.getId(), booking.getBookingReference());
        return PaymentPreparation.proceed(
                payment.getId(),
                booking.getTotalAmount(),
                booking.getCurrency(),
                request.paymentMethodToken(),
                request.idempotencyKey(),
                "Booking " + booking.getBookingReference());
    }

    // -----------------------------------------------------------------------
    // Transaction 2: apply the gateway's answer.
    // -----------------------------------------------------------------------

    /**
     * Re-locks the booking and its seats and settles the attempt.
     * <p>
     * The seat rows are re-verified against the booking's hold token here, not
     * trusted from transaction 1. Time passed during the gateway call, and in
     * that window the hold can legitimately have lapsed and the seats been
     * taken by somebody else - so the authority is what the locked rows say
     * now.
     */
    @Transactional
    public PaymentSettlement settle(UUID bookingId, UUID paymentId, PaymentGateway.PaymentResult result) {
        Instant now = Instant.now(clock);

        Booking booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(ErrorCode.BOOKING_NOT_FOUND));
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new DomainException(ErrorCode.PAYMENT_NOT_FOUND));

        List<ShowSeat> lockedSeats = lockSeatsOf(bookingId);

        if (!result.successful()) {
            return applyDecline(booking, payment, lockedSeats, result.failureReason(), now);
        }
        boolean holdIntact = lockedSeats.stream()
                .allMatch(showSeat -> showSeat.isValidHold(booking.getHoldToken(), now));
        return holdIntact
                ? applyConfirmation(booking, payment, lockedSeats, result.gatewayReference(), now)
                : applyLapsedHoldAfterCharge(booking, payment, lockedSeats, result.gatewayReference(), now);
    }

    /**
     * The gateway was unreachable, so the outcome is genuinely unknown.
     * <p>
     * The payment stays PENDING and the booking stays PAYMENT_PENDING with its
     * seats held. Asserting failure would risk telling a customer their card
     * was not charged when it may have been. The hold then lapses normally and
     * the sweeper frees the seats; the PENDING payment row remains for
     * reconciliation.
     */
    @Transactional
    public void recordGatewayUnavailable(UUID paymentId, String reason) {
        paymentRepository.findById(paymentId)
                .ifPresent(payment -> payment.recordGatewayUnavailable(reason));
        log.warn("Payment {} left PENDING after a gateway transport failure: {}", paymentId, reason);
    }

    /**
     * Records the refund issued because the hold lapsed after a successful
     * charge. Written as a normal {@code refunds} row so the money movement is
     * auditable in the same place as every other refund.
     */
    @Transactional
    public void recordCompensatingRefund(
            UUID bookingId,
            UUID paymentId,
            PaymentGateway.RefundResult refundResult,
            BigDecimal amount) {
        Instant now = Instant.now(clock);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new DomainException(ErrorCode.BOOKING_NOT_FOUND));
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new DomainException(ErrorCode.PAYMENT_NOT_FOUND));

        if (refundRepository.existsByBookingId(bookingId)) {
            return;
        }

        Refund refund = Refund.builder()
                .booking(booking)
                .payment(payment)
                .originalAmount(payment.getAmount())
                .refundPercentage(Money.HUNDRED)
                .refundAmount(Money.normalize(amount))
                .currency(payment.getCurrency())
                .hoursBeforeShow(refundCalculator.hoursUntil(booking.getShow().getStartsAt(), now))
                .status(RefundStatus.PENDING)
                .build();

        if (refundResult.successful()) {
            refund.markCompleted(refundResult.gatewayReference(), now);
        } else {
            refund.markFailed(refundResult.failureReason(), now);
        }
        refundRepository.save(refund);

        log.warn("Compensating refund of {} recorded for booking {}: the hold lapsed after the charge succeeded",
                amount, booking.getBookingReference());
    }

    // -----------------------------------------------------------------------
    // Settlement branches
    // -----------------------------------------------------------------------

    private PaymentSettlement applyConfirmation(
            Booking booking,
            Payment payment,
            List<ShowSeat> lockedSeats,
            String gatewayReference,
            Instant now) {
        lockedSeats.forEach(ShowSeat::confirm);
        booking.confirm(now);
        payment.markSuccess(gatewayReference, now);

        // Queued inside this transaction, delivered after it commits.
        notificationService.notifyBookingConfirmed(booking);

        log.info("Booking {} confirmed with payment {}", booking.getBookingReference(), payment.getId());
        return PaymentSettlement.confirmed(BookingResponse.from(booking));
    }

    private PaymentSettlement applyDecline(
            Booking booking,
            Payment payment,
            List<ShowSeat> lockedSeats,
            String failureReason,
            Instant now) {
        releaseSeatsHeldBy(lockedSeats, booking.getHoldToken());
        booking.markPaymentFailed();
        payment.markFailed(failureReason, now);
        discountService.releaseRedemption(booking.getId());
        notificationService.notifyPaymentFailed(booking, payment);

        log.info("Booking {} failed payment and released its seats: {}",
                booking.getBookingReference(), failureReason);
        return PaymentSettlement.declined(failureReason);
    }

    /**
     * Charged, but the seats are no longer ours.
     * <p>
     * The payment is recorded as SUCCESS because money genuinely moved -
     * pretending otherwise would make the books wrong. The booking expires,
     * any remaining redemption is released, and the orchestrator hands the
     * charge back through the gateway.
     */
    private PaymentSettlement applyLapsedHoldAfterCharge(
            Booking booking,
            Payment payment,
            List<ShowSeat> lockedSeats,
            String gatewayReference,
            Instant now) {
        payment.markSuccess(gatewayReference, now);
        releaseSeatsHeldBy(lockedSeats, booking.getHoldToken());
        booking.expire();
        discountService.releaseRedemption(booking.getId());

        log.error("Booking {} was charged but its hold had lapsed; a compensating refund is required",
                booking.getBookingReference());
        return PaymentSettlement.holdLapsedAfterCharge(new PaymentSettlement.Compensation(
                payment.getId(), gatewayReference, payment.getAmount(), payment.getCurrency()));
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private Optional<PaymentPreparation> replayIfKnownIdempotencyKey(Booking booking, String idempotencyKey) {
        Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        Payment payment = existing.get();
        if (!payment.getBooking().getId().equals(booking.getId())) {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST,
                    "This idempotency key was already used for a different booking");
        }
        return Optional.of(switch (payment.getStatus()) {
            case SUCCESS -> PaymentPreparation.alreadySucceeded(BookingResponse.from(booking));
            case FAILED -> PaymentPreparation.alreadyFailed(payment.getFailureReason());
            // Still in flight: another request holds this key and has not
            // settled yet. Reporting a conflict is safer than starting a
            // second charge under the same key.
            case PENDING -> throw new DomainException(
                    ErrorCode.PAYMENT_ALREADY_PROCESSED,
                    "A payment with this idempotency key is already in progress");
        });
    }

    private List<ShowSeat> lockSeatsOf(UUID bookingId) {
        List<UUID> showSeatIds = bookingSeatRepository.findShowSeatIdsByBookingId(bookingId);
        return showSeatIds.isEmpty() ? List.of() : showSeatRepository.lockByIds(showSeatIds);
    }

    /**
     * Releases only the rows still held under {@code holdToken}.
     * <p>
     * The token comparison is essential. If this hold had already lapsed, one
     * of these seats may now be held or booked by a different customer through
     * lazy expiry - releasing it on status alone would take a seat away from
     * someone who legitimately holds it.
     */
    private void releaseSeatsHeldBy(List<ShowSeat> lockedSeats, UUID holdToken) {
        lockedSeats.stream()
                .filter(showSeat -> showSeat.isHeldUnder(holdToken))
                .forEach(ShowSeat::release);
    }

    private void expireBookingAndReleaseSeats(Booking booking) {
        UUID holdToken = booking.getHoldToken();
        releaseSeatsHeldBy(lockSeatsOf(booking.getId()), holdToken);
        discountService.releaseRedemption(booking.getId());
        booking.expire();
    }

    private static void requireOwnership(Booking booking, UUID userId) {
        if (!booking.isOwnedBy(userId)) {
            throw new DomainException(
                    ErrorCode.UNAUTHORIZED_BOOKING_ACCESS,
                    "Booking %s belongs to another customer".formatted(booking.getId()));
        }
    }
}
