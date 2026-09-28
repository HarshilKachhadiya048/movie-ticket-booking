package com.harshil.movieticketbooking.discount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import com.harshil.movieticketbooking.discount.domain.DiscountCodeUsage;
import com.harshil.movieticketbooking.discount.domain.DiscountType;
import com.harshil.movieticketbooking.discount.repository.DiscountCodeRepository;
import com.harshil.movieticketbooking.discount.repository.DiscountCodeUsageRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Validation ordering and redemption bookkeeping, isolated from the database.
 * <p>
 * {@link com.harshil.movieticketbooking.discount.DiscountRedemptionIT} proves
 * the locking actually serialises concurrent redemptions; this proves the
 * decisions made once a code has been loaded.
 */
@ExtendWith(MockitoExtension.class)
class DiscountServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-09T09:00:00Z");
    private static final BigDecimal SUBTOTAL = new BigDecimal("1000.00");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CODE_ID = UUID.randomUUID();

    @Mock
    private DiscountCodeRepository discountCodeRepository;

    @Mock
    private DiscountCodeUsageRepository discountCodeUsageRepository;

    @InjectMocks
    private DiscountService discountService;

    private static DiscountCode.DiscountCodeBuilder usableCode() {
        return DiscountCode.builder()
                .code("SAVE20")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("20.00"))
                .minBookingAmount(BigDecimal.ZERO)
                .validFrom(NOW.minus(Duration.ofDays(1)))
                .validUntil(NOW.plus(Duration.ofDays(1)))
                .usedCount(0)
                .active(true);
    }

    private void givenCode(DiscountCode code) {
        when(discountCodeRepository.findIdByCode("SAVE20")).thenReturn(Optional.of(CODE_ID));
        when(discountCodeRepository.lockById(CODE_ID)).thenReturn(Optional.of(code));
    }

    @Nested
    @DisplayName("evaluate")
    class Evaluate {

        @Test
        void noCodeMeansNoDiscountAndNoDatabaseAccess() {
            assertThat(discountService.evaluate(null, USER_ID, SUBTOTAL, NOW)).isEmpty();
            assertThat(discountService.evaluate("   ", USER_ID, SUBTOTAL, NOW)).isEmpty();

            verifyNoInteractions(discountCodeRepository, discountCodeUsageRepository);
        }

        @Test
        void aUsableCodeYieldsItsDiscount() {
            givenCode(usableCode().build());

            Optional<DiscountApplication> application =
                    discountService.evaluate("SAVE20", USER_ID, SUBTOTAL, NOW);

            assertThat(application).isPresent();
            assertThat(application.get().discountAmount()).isEqualByComparingTo("200.00");
        }

        /**
         * The code is resolved to an id and only then loaded under the lock.
         * Loading it first would leave a cached instance that the locking
         * select would not refresh, so the limit check could run against a
         * stale counter.
         */
        @Test
        void theCodeIsAlwaysLoadedThroughTheLockingQuery() {
            givenCode(usableCode().build());

            discountService.evaluate("SAVE20", USER_ID, SUBTOTAL, NOW);

            verify(discountCodeRepository).findIdByCode("SAVE20");
            verify(discountCodeRepository).lockById(CODE_ID);
        }

        @Test
        void anUnknownCodeIsRejected() {
            when(discountCodeRepository.findIdByCode("NOPE")).thenReturn(Optional.empty());

            assertThatErrorCode(
                    () -> discountService.evaluate("NOPE", USER_ID, SUBTOTAL, NOW),
                    ErrorCode.DISCOUNT_INVALID);
        }

        @Test
        void anInactiveCodeIsRejected() {
            givenCode(usableCode().active(false).build());

            assertThatErrorCode(
                    () -> discountService.evaluate("SAVE20", USER_ID, SUBTOTAL, NOW),
                    ErrorCode.DISCOUNT_INACTIVE);
        }

        @Test
        void aCodeOutsideItsWindowIsRejected() {
            givenCode(usableCode()
                    .validFrom(NOW.plus(Duration.ofDays(1)))
                    .validUntil(NOW.plus(Duration.ofDays(2)))
                    .build());

            assertThatErrorCode(
                    () -> discountService.evaluate("SAVE20", USER_ID, SUBTOTAL, NOW),
                    ErrorCode.DISCOUNT_EXPIRED);
        }

        @Test
        void aSubtotalBelowTheMinimumIsRejected() {
            givenCode(usableCode().minBookingAmount(new BigDecimal("5000.00")).build());

            assertThatErrorCode(
                    () -> discountService.evaluate("SAVE20", USER_ID, SUBTOTAL, NOW),
                    ErrorCode.DISCOUNT_MIN_AMOUNT_NOT_MET);
        }

        @Test
        void anExhaustedCodeIsRejected() {
            givenCode(usableCode().usageLimit(5).usedCount(5).build());

            assertThatErrorCode(
                    () -> discountService.evaluate("SAVE20", USER_ID, SUBTOTAL, NOW),
                    ErrorCode.DISCOUNT_USAGE_LIMIT_REACHED);
        }

        @Test
        void aCustomerOverTheirPerUserLimitIsRejected() {
            givenCode(usableCode().perUserLimit(2).build());
            when(discountCodeUsageRepository.countByDiscountCodeIdAndUserId(any(), any())).thenReturn(2L);

            assertThatErrorCode(
                    () -> discountService.evaluate("SAVE20", USER_ID, SUBTOTAL, NOW),
                    ErrorCode.DISCOUNT_USER_LIMIT_REACHED);
        }

        /**
         * Only worth one query, and only when a limit is actually configured.
         */
        @Test
        void thePerUserCountIsNotQueriedWhenThereIsNoPerUserLimit() {
            givenCode(usableCode().perUserLimit(null).build());

            discountService.evaluate("SAVE20", USER_ID, SUBTOTAL, NOW);

            verify(discountCodeUsageRepository, never()).countByDiscountCodeIdAndUserId(any(), any());
        }
    }

    @Nested
    @DisplayName("redemption")
    class Redemption {

        @Test
        void recordingIncrementsTheCounterAndWritesAUsageRow() {
            DiscountCode code = usableCode().usageLimit(10).build();
            UUID bookingId = UUID.randomUUID();

            discountService.recordRedemption(
                    new DiscountApplication(code, new BigDecimal("200.00")), USER_ID, bookingId);

            assertThat(code.getUsedCount()).isEqualTo(1);
            verify(discountCodeUsageRepository).save(any(DiscountCodeUsage.class));
        }

        /**
         * Release is reached from the sweeper, payment failure and
         * cancellation, which can legitimately overlap - so it has to be a
         * no-op when there is nothing to release.
         */
        @Test
        void releasingABookingWithNoRedemptionIsANoOp() {
            UUID bookingId = UUID.randomUUID();
            when(discountCodeUsageRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());

            discountService.releaseRedemption(bookingId);

            verify(discountCodeRepository, never()).lockById(any());
            verify(discountCodeUsageRepository, never()).delete(any());
        }

        @Test
        void releasingDecrementsTheCounterAndDeletesTheUsageRow() {
            DiscountCode code = usableCode().usedCount(3).build();
            UUID bookingId = UUID.randomUUID();
            DiscountCodeUsage usage = DiscountCodeUsage.builder()
                    .discountCode(code)
                    .userId(USER_ID)
                    .bookingId(bookingId)
                    .discountAmount(new BigDecimal("200.00"))
                    .build();

            when(discountCodeUsageRepository.findByBookingId(bookingId)).thenReturn(Optional.of(usage));
            when(discountCodeRepository.lockById(any())).thenReturn(Optional.of(code));

            discountService.releaseRedemption(bookingId);

            assertThat(code.getUsedCount()).isEqualTo(2);
            verify(discountCodeUsageRepository).delete(usage);
        }
    }

    private static void assertThatErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(expected);
    }
}
