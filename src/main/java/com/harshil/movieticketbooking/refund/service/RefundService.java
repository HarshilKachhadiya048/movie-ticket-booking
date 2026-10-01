package com.harshil.movieticketbooking.refund.service;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.refund.domain.Refund;
import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import com.harshil.movieticketbooking.refund.repository.RefundPolicyRepository;
import com.harshil.movieticketbooking.refund.repository.RefundRepository;
import com.harshil.movieticketbooking.show.domain.Show;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides what a cancellation is worth.
 * <p>
 * <b>Nothing about the ladder is hard-coded.</b> The default - 100% beyond
 * 24 hours, 50% between 12 and 24, nothing under 12 - exists purely as rows in
 * {@code refund_policy_rules}, editable through the admin API. This
 * class resolves which policy applies and delegates the arithmetic to
 * {@link RefundCalculator}.
 * <p>
 * <b>Resolution order:</b> the show's theater may define its own policy;
 * otherwise the single platform default applies. A venue can therefore run its
 * own terms with no code change.
 * <p>
 * <b>Failing closed.</b> If no policy resolves at all, the assessment is zero
 * rather than full. An operator who forgot to configure a policy under-refunds
 * visibly instead of refunding everything silently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundPolicyRepository refundPolicyRepository;
    private final RefundRepository refundRepository;
    private final RefundCalculator refundCalculator;

    /**
     * Works out the refund for cancelling {@code booking} at {@code now}.
     *
     * @param paidAmount the amount actually charged, taken from the successful
     *                   payment rather than from the booking total - they can
     *                   only differ if something went wrong, and the money that
     *                   moved is the honest basis for giving money back
     */
    @Transactional(readOnly = true)
    public RefundAssessment assess(Booking booking, BigDecimal paidAmount, Instant now) {
        Show show = booking.getShow();
        BigDecimal hoursBeforeShow = refundCalculator.hoursUntil(show.getStartsAt(), now);
        RefundPolicy policy = resolvePolicy(show).orElse(null);

        RefundAssessment assessment = refundCalculator.assess(policy, paidAmount, hoursBeforeShow);
        log.debug("Booking {} cancelled {}h before the show: {}% of {} = {} (policy {})",
                booking.getBookingReference(),
                hoursBeforeShow,
                assessment.percentage(),
                paidAmount,
                assessment.refundAmount(),
                policy == null ? "none" : policy.getName());
        return assessment;
    }

    /**
     * The policy governing a show: its theater's own, else the platform
     * default. Both uniqueness rules are enforced by partial unique indexes,
     * so neither lookup can return more than one row.
     */
    @Transactional(readOnly = true)
    public Optional<RefundPolicy> resolvePolicy(Show show) {
        UUID theaterId = show.getScreen().getTheater().getId();
        return refundPolicyRepository.findActiveByTheaterId(theaterId)
                .or(refundPolicyRepository::findActiveDefault);
    }

    @Transactional(readOnly = true)
    public Optional<Refund> findForBooking(UUID bookingId) {
        return refundRepository.findByBookingId(bookingId);
    }
}
