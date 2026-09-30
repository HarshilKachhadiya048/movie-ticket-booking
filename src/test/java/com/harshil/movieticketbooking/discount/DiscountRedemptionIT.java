package com.harshil.movieticketbooking.discount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.PayBookingRequest;
import com.harshil.movieticketbooking.booking.service.BookingCancellationService;
import com.harshil.movieticketbooking.booking.service.BookingPaymentService;
import com.harshil.movieticketbooking.booking.service.CreateHoldCommand;
import com.harshil.movieticketbooking.booking.service.SeatHoldService;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import com.harshil.movieticketbooking.discount.domain.DiscountType;
import com.harshil.movieticketbooking.discount.repository.DiscountCodeRepository;
import com.harshil.movieticketbooking.payment.gateway.PaymentMethodToken;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.TestScenario;
import com.harshil.movieticketbooking.user.domain.Role;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Discount validation, redemption accounting and the races around usage
 * limits.
 */
class DiscountRedemptionIT extends AbstractIntegrationTest {

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private BookingPaymentService bookingPaymentService;

    @Autowired
    private BookingCancellationService bookingCancellationService;

    @Autowired
    private DiscountCodeRepository discountCodeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Nested
    @DisplayName("applying a code")
    class Applying {

        @Test
        void aPercentageCodeReducesTheTotalAtHoldTime() {
            TestScenario scenario = testData.createBookableShow();
            testData.createDiscountCode("SAVE25", DiscountType.PERCENTAGE,
                    new BigDecimal("25.00"), null, BigDecimal.ZERO, null, null);

            BookingResponse booking = hold(scenario, "SAVE25");

            assertThat(booking.subtotalAmount()).isEqualByComparingTo("700.00");
            assertThat(booking.discountAmount()).isEqualByComparingTo("175.00");
            assertThat(booking.totalAmount()).isEqualByComparingTo("525.00");
            assertThat(booking.discountCode()).isEqualTo("SAVE25");
        }

        @Test
        void theConfiguredCapLimitsThePercentage() {
            TestScenario scenario = testData.createBookableShow();
            testData.createDiscountCode("CAPPED", DiscountType.PERCENTAGE,
                    new BigDecimal("50.00"), new BigDecimal("100.00"), BigDecimal.ZERO, null, null);

            BookingResponse booking = hold(scenario, "CAPPED");

            assertThat(booking.discountAmount()).isEqualByComparingTo("100.00");
            assertThat(booking.totalAmount()).isEqualByComparingTo("600.00");
        }

        @Test
        void codesAreMatchedCaseInsensitively() {
            TestScenario scenario = testData.createBookableShow();
            testData.createDiscountCode("MIXEDCASE", DiscountType.FIXED_AMOUNT,
                    new BigDecimal("50.00"), null, BigDecimal.ZERO, null, null);

            BookingResponse booking = hold(scenario, "mixedcase");

            assertThat(booking.discountAmount()).isEqualByComparingTo("50.00");
        }

        @Test
        void anUnknownCodeIsRejected() {
            TestScenario scenario = testData.createBookableShow();

            assertThatErrorCode(() -> hold(scenario, "NOPE"), ErrorCode.DISCOUNT_INVALID);
        }

        @Test
        void aBookingBelowTheMinimumIsRejected() {
            TestScenario scenario = testData.createBookableShow();
            testData.createDiscountCode("BIGSPEND", DiscountType.FIXED_AMOUNT,
                    new BigDecimal("50.00"), null, new BigDecimal("5000.00"), null, null);

            assertThatErrorCode(() -> hold(scenario, "BIGSPEND"), ErrorCode.DISCOUNT_MIN_AMOUNT_NOT_MET);
        }

        /**
         * The show is scheduled well beyond the code's validity window, so
         * that advancing past the code's expiry does not also push the show
         * into the past - which would fail with SHOW_ALREADY_STARTED and tell
         * us nothing about discounts.
         */
        @Test
        void anExpiredCodeIsRejected() {
            TestScenario scenario = testData.createBookableShow(
                    java.time.Instant.now(clock).plus(java.time.Duration.ofDays(60)));
            testData.createDiscountCode("SOONGONE", DiscountType.PERCENTAGE,
                    new BigDecimal("10.00"), null, BigDecimal.ZERO, null, null);

            // The fixture's validity window closes 30 days out; step past it.
            clock.advance(java.time.Duration.ofDays(31));

            assertThatErrorCode(() -> hold(scenario, "SOONGONE"), ErrorCode.DISCOUNT_EXPIRED);
        }

        @Test
        void theSameCustomerCannotExceedThePerUserLimit() {
            TestScenario scenario = testData.createBookableShow();
            testData.createDiscountCode("ONEPERUSER", DiscountType.PERCENTAGE,
                    new BigDecimal("10.00"), null, BigDecimal.ZERO, null, 1);

            hold(scenario, scenario.premiumSeatIds(), "ONEPERUSER", scenario.customerId());

            assertThatErrorCode(
                    () -> hold(scenario, scenario.regularSeatIds().subList(0, 2), "ONEPERUSER",
                            scenario.customerId()),
                    ErrorCode.DISCOUNT_USER_LIMIT_REACHED);
        }

        @Test
        void aDifferentCustomerIsUnaffectedByAnotherCustomersPerUserLimit() {
            TestScenario scenario = testData.createBookableShow();
            testData.createDiscountCode("SHARED", DiscountType.PERCENTAGE,
                    new BigDecimal("10.00"), null, BigDecimal.ZERO, null, 1);

            hold(scenario, scenario.premiumSeatIds(), "SHARED", scenario.customerId());
            BookingResponse other = hold(scenario, scenario.regularSeatIds().subList(0, 2), "SHARED",
                    scenario.otherCustomerId());

            assertThat(other.discountAmount()).isPositive();
        }
    }

    @Nested
    @DisplayName("redemption accounting")
    class Accounting {

        @Test
        void holdingRecordsARedemption() {
            TestScenario scenario = testData.createBookableShow();
            DiscountCode code = testData.createDiscountCode("COUNTED", DiscountType.PERCENTAGE,
                    new BigDecimal("10.00"), null, BigDecimal.ZERO, 5, null);

            hold(scenario, "COUNTED");

            assertThat(usedCount(code.getId())).isEqualTo(1);
            assertThat(usageRows(code.getId())).isEqualTo(1);
        }

        @Test
        void aDeclinedPaymentReturnsTheCodeToCirculation() {
            TestScenario scenario = testData.createBookableShow();
            DiscountCode code = testData.createDiscountCode("REFUNDABLE", DiscountType.PERCENTAGE,
                    new BigDecimal("10.00"), null, BigDecimal.ZERO, 1, null);
            BookingResponse booking = hold(scenario, "REFUNDABLE");

            assertThatThrownBy(() -> bookingPaymentService.pay(
                    booking.bookingId(), scenario.customerId(),
                    new PayBookingRequest(PaymentMethodToken.FAILURE.token(), "idem-discount-fail")))
                    .isInstanceOf(DomainException.class);

            assertThat(usedCount(code.getId())).isZero();
            assertThat(usageRows(code.getId())).isZero();
        }

        @Test
        void cancellingReturnsTheCodeToCirculation() {
            TestScenario scenario = testData.createBookableShow();
            DiscountCode code = testData.createDiscountCode("CANCELME", DiscountType.PERCENTAGE,
                    new BigDecimal("10.00"), null, BigDecimal.ZERO, 1, null);
            BookingResponse booking = hold(scenario, "CANCELME");
            bookingPaymentService.pay(booking.bookingId(), scenario.customerId(),
                    new PayBookingRequest(PaymentMethodToken.SUCCESS.token(), "idem-discount-cancel"));

            bookingCancellationService.cancel(booking.bookingId(), scenario.customerId());

            assertThat(usedCount(code.getId())).isZero();
        }

        /** The mirror invariant: the counter equals the number of live rows. */
        @Test
        void usedCountAlwaysMatchesTheNumberOfUsageRows() {
            TestScenario scenario = testData.createBookableShow();
            DiscountCode code = testData.createDiscountCode("MIRROR", DiscountType.PERCENTAGE,
                    new BigDecimal("10.00"), null, BigDecimal.ZERO, 10, null);

            hold(scenario, scenario.premiumSeatIds(), "MIRROR", scenario.customerId());
            hold(scenario, scenario.regularSeatIds().subList(0, 2), "MIRROR", scenario.otherCustomerId());

            assertThat(usedCount(code.getId())).isEqualTo((int) usageRows(code.getId()));
            assertThat(usedCount(code.getId())).isEqualTo(2);
        }
    }

    /**
     * Checking "has this been used 100 times?" and then incrementing is a
     * read-modify-write race. The redemption path takes a pessimistic row lock
     * on the code so that concurrent customers serialise inside PostgreSQL
     * rather than all reading the same stale count.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("a usage limit cannot be raced past by concurrent customers")
    void concurrentRedemptionsRespectTheOverallUsageLimit() throws Exception {
        int limit = 3;
        int contenders = 12;

        TestScenario scenario = testData.createBookableShow();
        DiscountCode code = testData.createDiscountCode("SCARCE", DiscountType.PERCENTAGE,
                new BigDecimal("10.00"), null, BigDecimal.ZERO, limit, null);

        // Every customer books a different seat, so the only contended
        // resource is the discount code itself, not the seats.
        List<UUID> seats = scenario.seatIds();
        List<UUID> customers = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            customers.add(testData.saveUser("greedy-%d-%s".formatted(i, UUID.randomUUID()), Role.CUSTOMER).getId());
        }

        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger redeemed = new AtomicInteger();
        AtomicInteger rejectedForLimit = new AtomicInteger();
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger seatIndex = new AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID customerId : customers) {
                executor.submit(() -> {
                    int mySeat = seatIndex.getAndIncrement() % seats.size();
                    ready.countDown();
                    try {
                        startGate.await();
                        seatHoldService.createHold(new CreateHoldCommand(
                                scenario.showId(), List.of(seats.get(mySeat)), "SCARCE", customerId));
                        redeemed.incrementAndGet();
                    } catch (DomainException ex) {
                        if (ex.errorCode() == ErrorCode.DISCOUNT_USAGE_LIMIT_REACHED) {
                            rejectedForLimit.incrementAndGet();
                        } else if (ex.errorCode() != ErrorCode.SEAT_ALREADY_HELD) {
                            unexpected.add(ex.errorCode() + ": " + ex.getMessage());
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException ex) {
                        unexpected.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            startGate.countDown();
        }

        assertThat(unexpected).as("unexpected failures: %s", unexpected).isEmpty();
        assertThat(redeemed.get())
                .as("the code must not be redeemed more times than its limit allows")
                .isLessThanOrEqualTo(limit);
        assertThat(usedCount(code.getId()))
                .as("the stored counter must match the redemptions that actually happened")
                .isEqualTo(redeemed.get());
        assertThat(usageRows(code.getId())).isEqualTo(redeemed.get());
        assertThat(rejectedForLimit.get()).isPositive();
    }

    // -----------------------------------------------------------------------

    private BookingResponse hold(TestScenario scenario, String discountCode) {
        return hold(scenario, scenario.premiumSeatIds(), discountCode, scenario.customerId());
    }

    private BookingResponse hold(TestScenario scenario, List<UUID> seatIds, String code, UUID customerId) {
        return seatHoldService.createHold(
                new CreateHoldCommand(scenario.showId(), seatIds, code, customerId));
    }

    private static void assertThatErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(expected);
    }

    private int usedCount(UUID discountCodeId) {
        return discountCodeRepository.findById(discountCodeId).orElseThrow().getUsedCount();
    }

    private long usageRows(UUID discountCodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM discount_code_usages WHERE discount_code_id = ?",
                Long.class, discountCodeId);
    }
}
