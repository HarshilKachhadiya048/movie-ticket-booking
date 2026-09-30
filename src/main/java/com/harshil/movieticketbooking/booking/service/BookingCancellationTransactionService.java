package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.CancellationResponse;
import com.harshil.movieticketbooking.booking.repository.BookingRepository;
import com.harshil.movieticketbooking.booking.repository.BookingSeatRepository;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.discount.service.DiscountService;
import com.harshil.movieticketbooking.notification.service.NotificationService;
import com.harshil.movieticketbooking.payment.domain.Payment;
import com.harshil.movieticketbooking.payment.domain.PaymentStatus;
import com.harshil.movieticketbooking.payment.gateway.PaymentGateway;
import com.harshil.movieticketbooking.payment.repository.PaymentRepository;
import com.harshil.movieticketbooking.refund.config.RefundProperties;
import com.harshil.movieticketbooking.refund.domain.Refund;
import com.harshil.movieticketbooking.refund.domain.RefundStatus;
import com.harshil.movieticketbooking.refund.dto.RefundResponse;
import com.harshil.movieticketbooking.refund.repository.RefundRepository;
import com.harshil.movieticketbooking.refund.service.RefundAssessment;
import com.harshil.movieticketbooking.refund.service.RefundCalculator;
import com.harshil.movieticketbooking.refund.service.RefundService;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.repository.ShowSeatRepository;
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
 * The database half of cancellation.
 * <p>
 * Split from {@link BookingCancellationService} for the same reason the
 * payment flow is split: the gateway refund must happen outside a transaction,
 * and self-invocation would bypass the {@code @Transactional} proxy and
 * quietly merge the two.
 * <p>
 * <b>Ordering.</b> The booking is cancelled, the seats are released and the
 * refund row is written in one transaction; the gateway is called afterwards.
 * The customer's cancellation therefore succeeds, and the seats go back on
 * sale immediately, even if the payment provider is slow or down. A refund the
 * gateway rejects is left as a FAILED row for operational follow-up rather
 * than being rolled back into "your cancellation didn't work".
 * <p>
 * <b>Idempotency.</b> Cancelling an already-cancelled booking replays the
 * original outcome instead of failing or refunding twice. Three things enforce
 * it: the booking row lock serialises concurrent attempts, the status check
 * short-circuits, and {@code uq_refunds_booking} makes a second refund row
 * impossible even in principle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingCancellationTransactionService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final ShowSeatRepository showSeatRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final RefundService refundService;
    private final RefundCalculator refundCalculator;
    private final DiscountService discountService;
    private final NotificationService notificationService;
    private final RefundProperties refundProperties;
    private final Clock clock;

    @Transactional
    public CancellationOutcome cancel(UUID bookingId, UUID userId) {
        Instant now = Instant.now(clock);

        Booking booking = bookingRepository.lockById(bookingId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.BOOKING_NOT_FOUND, "Booking %s does not exist".formatted(bookingId)));
        requireOwnership(booking, userId);

        if (booking.getStatus() == BookingStatus.CANCELLED || booking.getStatus() == BookingStatus.REFUNDED) {
            return CancellationOutcome.alreadyCancelled(respond(booking, existingRefund(bookingId)));
        }
        if (booking.getStatus().holdsSeats()) {
            return cancelUnpaidBooking(booking, now);
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new DomainException(
                    ErrorCode.BOOKING_NOT_CANCELLABLE,
                    "Booking %s is %s".formatted(booking.getBookingReference(), booking.getStatus()));
        }
        return cancelConfirmedBooking(booking, now);
    }

    /** Records the gateway's answer and tells the customer money is on its way. */
    @Transactional
    public RefundResponse completeRefund(UUID refundId, PaymentGateway.RefundResult result) {
        Instant now = Instant.now(clock);
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new DomainException(ErrorCode.REFUND_NOT_ALLOWED, "Refund not found"));

        if (result.successful()) {
            refund.markCompleted(result.gatewayReference(), now);
            notificationService.notifyRefundCompleted(refund.getBooking(), refund);
        } else {
            refund.markFailed(result.failureReason(), now);
            log.error("Refund {} for booking {} was rejected by the gateway: {}",
                    refundId, refund.getBooking().getBookingReference(), result.failureReason());
        }
        return RefundResponse.from(refund);
    }

    // -----------------------------------------------------------------------
    // Cancellation paths
    // -----------------------------------------------------------------------

    /**
     * A hold the customer abandoned, or one whose payment never completed.
     * Nothing was charged, so there is nothing to refund - just release.
     */
    private CancellationOutcome cancelUnpaidBooking(Booking booking, Instant now) {
        releaseSeatsHeldBy(lockSeatsOf(booking.getId()), booking.getHoldToken());
        discountService.releaseRedemption(booking.getId());
        booking.cancel(now);
        notificationService.notifyBookingCancelled(booking);

        log.info("Booking {} cancelled before payment", booking.getBookingReference());
        return CancellationOutcome.settled(respond(booking, Optional.empty()));
    }

    private CancellationOutcome cancelConfirmedBooking(Booking booking, Instant now) {
        Show show = booking.getShow();
        validateCancellationWindow(booking, show, now);

        Payment payment = paymentRepository
                .findFirstByBookingIdAndStatus(booking.getId(), PaymentStatus.SUCCESS)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PAYMENT_NOT_FOUND,
                        "Booking %s is confirmed but has no successful payment".formatted(
                                booking.getBookingReference())));

        RefundAssessment assessment = refundService.assess(booking, payment.getAmount(), now);
        Refund refund = persistRefund(booking, payment, assessment, now);

        // Confirmed seats belong to this booking alone, so they release
        // unconditionally - there is no competing hold token to respect.
        lockSeatsOf(booking.getId()).forEach(ShowSeat::release);
        discountService.releaseRedemption(booking.getId());

        if (!assessment.isRefundable()) {
            // Nothing to move, so there is no gateway call to make and the
            // refund settles here. The row is still written: the customer can
            // then see which policy band applied and why it came to zero,
            // rather than being left to infer it from silence.
            booking.cancel(now);
            refund.markCompleted(null, now);
            notificationService.notifyBookingCancelled(booking);

            log.info("Booking {} cancelled with no refund due ({}% band)",
                    booking.getBookingReference(), assessment.percentage());
            return CancellationOutcome.settled(respond(booking, Optional.of(refund)));
        }

        booking.markRefunded(now);
        notificationService.notifyBookingCancelled(booking);

        log.info("Booking {} cancelled; refund of {} pending at the gateway",
                booking.getBookingReference(), assessment.refundAmount());
        return CancellationOutcome.refundDue(
                respond(booking, Optional.of(refund)),
                new CancellationOutcome.RefundInstruction(
                        refund.getId(),
                        payment.getGatewayReference(),
                        refund.getRefundAmount(),
                        refund.getCurrency()));
    }

    /**
     * Cancellation is allowed right up to the configured cut-off, which
     * defaults to the show's start time. Note that being <em>allowed</em> to
     * cancel and being <em>owed</em> money are separate questions: the policy
     * may well return 0% inside this window.
     */
    private void validateCancellationWindow(Booking booking, Show show, Instant now) {
        Instant cutoff = show.getStartsAt().minus(refundProperties.cancellationCutoffBeforeShow());
        if (!now.isBefore(cutoff)) {
            throw new DomainException(
                    ErrorCode.BOOKING_NOT_CANCELLABLE,
                    "Booking %s can no longer be cancelled; the show starts at %s".formatted(
                            booking.getBookingReference(), show.getStartsAt()))
                    .withDetail("showStartsAt", show.getStartsAt())
                    .withDetail("cancellationCutoff", cutoff);
        }
    }

    private Refund persistRefund(Booking booking, Payment payment, RefundAssessment assessment, Instant now) {
        return refundRepository.saveAndFlush(Refund.builder()
                .booking(booking)
                .payment(payment)
                .refundPolicy(assessment.policy())
                .refundPolicyRule(assessment.rule())
                .originalAmount(Money.normalize(assessment.originalAmount()))
                .refundPercentage(assessment.percentage())
                .refundAmount(Money.normalize(assessment.refundAmount()))
                .currency(payment.getCurrency())
                .hoursBeforeShow(refundCalculator.hoursUntil(booking.getShow().getStartsAt(), now))
                .status(RefundStatus.PENDING)
                .build());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Optional<Refund> existingRefund(UUID bookingId) {
        return refundRepository.findByBookingId(bookingId);
    }

    private CancellationResponse respond(Booking booking, Optional<Refund> refund) {
        return new CancellationResponse(
                BookingResponse.from(booking),
                refund.map(RefundResponse::from).orElse(null));
    }

    private List<ShowSeat> lockSeatsOf(UUID bookingId) {
        List<UUID> showSeatIds = bookingSeatRepository.findShowSeatIdsByBookingId(bookingId);
        return showSeatIds.isEmpty() ? List.of() : showSeatRepository.lockByIds(showSeatIds);
    }

    private void releaseSeatsHeldBy(List<ShowSeat> lockedSeats, UUID holdToken) {
        lockedSeats.stream()
                .filter(showSeat -> showSeat.isHeldUnder(holdToken))
                .forEach(ShowSeat::release);
    }

    private static void requireOwnership(Booking booking, UUID userId) {
        if (!booking.isOwnedBy(userId)) {
            throw new DomainException(
                    ErrorCode.UNAUTHORIZED_BOOKING_ACCESS,
                    "Booking %s belongs to another customer".formatted(booking.getId()));
        }
    }
}
