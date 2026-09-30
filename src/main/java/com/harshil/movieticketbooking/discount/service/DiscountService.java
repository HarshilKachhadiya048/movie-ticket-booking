package com.harshil.movieticketbooking.discount.service;

import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import com.harshil.movieticketbooking.discount.domain.DiscountCodeUsage;
import com.harshil.movieticketbooking.discount.repository.DiscountCodeRepository;
import com.harshil.movieticketbooking.discount.repository.DiscountCodeUsageRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Validates discount codes and tracks their redemption.
 * <p>
 * <b>Why usage limits are not racy.</b> Checking "has this code been used 100
 * times?" and then incrementing the counter is a classic read-modify-write
 * race: two customers can both read 99 and both succeed. {@link #evaluate}
 * therefore loads the code with {@code SELECT ... FOR UPDATE}, so concurrent
 * redemptions of the same code are serialised inside PostgreSQL. The per-user
 * count is read while that lock is held, which makes it safe too.
 * <p>
 * <b>Why every method is {@link Propagation#MANDATORY}.</b> The lock only
 * means anything for the lifetime of the transaction that took it. Declaring
 * MANDATORY makes it impossible to call these methods outside one: a caller
 * that forgot gets an immediate, obvious failure rather than a subtly
 * unprotected redemption.
 * <p>
 * <b>Reserve now, release on failure.</b> A redemption is recorded when the
 * hold is created, so a customer who has a code in hand cannot have it taken
 * by someone else while they pay. If the booking never completes - the hold
 * lapses, the payment is declined, the customer cancels - the redemption is
 * released and the code returns to circulation. Deleting the usage row and
 * decrementing the counter together keeps
 * {@code used_count == COUNT(discount_code_usages)} true at all times.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscountService {

    private final DiscountCodeRepository discountCodeRepository;
    private final DiscountCodeUsageRepository discountCodeUsageRepository;

    /**
     * Validates a code against every configured limit and computes the
     * discount, leaving the code row locked for the rest of the transaction.
     *
     * @param rawCode  the customer-supplied code; blank or null means "no
     *                 discount" and yields an empty result rather than an error
     * @param subtotal the pre-discount booking total
     * @return the application to attach to the booking, or empty if no code
     *         was supplied
     * @throws DomainException if the code exists but cannot be used
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DiscountApplication> evaluate(
            String rawCode,
            UUID userId,
            BigDecimal subtotal,
            Instant now) {
        if (!StringUtils.hasText(rawCode)) {
            return Optional.empty();
        }
        String code = rawCode.trim();

        // Resolve to an id first, then load under the lock: see
        // DiscountCodeRepository#findIdByCode for why this is two steps.
        UUID discountCodeId = discountCodeRepository.findIdByCode(code)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.DISCOUNT_INVALID, "Discount code '%s' does not exist".formatted(code)));

        DiscountCode discountCode = discountCodeRepository.lockById(discountCodeId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.DISCOUNT_INVALID, "Discount code '%s' does not exist".formatted(code)));

        validateUsable(discountCode, userId, subtotal, now);

        BigDecimal discountAmount = discountCode.discountFor(subtotal);
        log.debug("Discount {} applies {} to a subtotal of {}", code, discountAmount, subtotal);
        return Optional.of(new DiscountApplication(discountCode, discountAmount));
    }

    /**
     * Records the redemption. Must be called in the same transaction as
     * {@link #evaluate}, while its row lock still stands.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRedemption(DiscountApplication application, UUID userId, UUID bookingId) {
        DiscountCode discountCode = application.discountCode();
        discountCode.recordRedemption();

        discountCodeUsageRepository.save(DiscountCodeUsage.builder()
                .discountCode(discountCode)
                .userId(userId)
                .bookingId(bookingId)
                .discountAmount(Money.normalize(application.discountAmount()))
                .build());

        log.debug("Recorded redemption of {} by user {} for booking {}",
                discountCode.getCode(), userId, bookingId);
    }

    /**
     * Returns a code to circulation when its booking does not complete.
     * <p>
     * Idempotent: a booking with no redemption, or one already released, is a
     * no-op. That matters because release is reached from several paths -
     * sweeper, payment failure, cancellation - which can legitimately overlap.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseRedemption(UUID bookingId) {
        Optional<DiscountCodeUsage> usage = discountCodeUsageRepository.findByBookingId(bookingId);
        if (usage.isEmpty()) {
            return;
        }

        DiscountCodeUsage discountCodeUsage = usage.get();
        UUID discountCodeId = discountCodeUsage.getDiscountCode().getId();

        // Lock before decrementing, for the same reason redemption locks.
        discountCodeRepository.lockById(discountCodeId)
                .ifPresent(DiscountCode::releaseRedemption);

        discountCodeUsageRepository.delete(discountCodeUsage);
        log.debug("Released discount redemption for booking {}", bookingId);
    }

    private void validateUsable(DiscountCode discountCode, UUID userId, BigDecimal subtotal, Instant now) {
        if (!discountCode.isActive()) {
            throw new DomainException(
                    ErrorCode.DISCOUNT_INACTIVE,
                    "Discount code '%s' is not active".formatted(discountCode.getCode()));
        }
        if (!discountCode.isWithinValidityWindow(now)) {
            throw new DomainException(
                    ErrorCode.DISCOUNT_EXPIRED,
                    "Discount code '%s' is only valid between %s and %s".formatted(
                            discountCode.getCode(), discountCode.getValidFrom(), discountCode.getValidUntil()));
        }
        if (!discountCode.meetsMinimumBookingAmount(subtotal)) {
            throw new DomainException(
                    ErrorCode.DISCOUNT_MIN_AMOUNT_NOT_MET,
                    "Discount code '%s' requires a minimum booking amount of %s".formatted(
                            discountCode.getCode(), discountCode.getMinBookingAmount()))
                    .withDetail("minBookingAmount", discountCode.getMinBookingAmount())
                    .withDetail("subtotal", subtotal);
        }
        if (!discountCode.hasRemainingUses()) {
            throw new DomainException(
                    ErrorCode.DISCOUNT_USAGE_LIMIT_REACHED,
                    "Discount code '%s' has been fully redeemed".formatted(discountCode.getCode()));
        }
        validatePerUserLimit(discountCode, userId);
    }

    private void validatePerUserLimit(DiscountCode discountCode, UUID userId) {
        Integer perUserLimit = discountCode.getPerUserLimit();
        if (perUserLimit == null) {
            return;
        }
        long alreadyUsed = discountCodeUsageRepository.countByDiscountCodeIdAndUserId(
                discountCode.getId(), userId);
        if (alreadyUsed >= perUserLimit) {
            throw new DomainException(
                    ErrorCode.DISCOUNT_USER_LIMIT_REACHED,
                    "Discount code '%s' may be used at most %d time(s) per customer".formatted(
                            discountCode.getCode(), perUserLimit))
                    .withDetail("perUserLimit", perUserLimit);
        }
    }
}
