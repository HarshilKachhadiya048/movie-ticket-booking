package com.harshil.movieticketbooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.CancellationResponse;
import com.harshil.movieticketbooking.booking.dto.PayBookingRequest;
import com.harshil.movieticketbooking.booking.service.BookingCancellationService;
import com.harshil.movieticketbooking.booking.service.BookingPaymentService;
import com.harshil.movieticketbooking.booking.service.BookingQueryService;
import com.harshil.movieticketbooking.booking.service.CreateHoldCommand;
import com.harshil.movieticketbooking.booking.service.SeatHoldService;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.notification.domain.NotificationType;
import com.harshil.movieticketbooking.payment.domain.PaymentStatus;
import com.harshil.movieticketbooking.payment.gateway.PaymentMethodToken;
import com.harshil.movieticketbooking.payment.repository.PaymentRepository;
import com.harshil.movieticketbooking.refund.domain.RefundStatus;
import com.harshil.movieticketbooking.refund.repository.RefundRepository;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.TestDataFactory;
import com.harshil.movieticketbooking.support.TestScenario;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The booking lifecycle end to end: hold, pay, confirm, cancel, refund - plus
 * every way each step can fail.
 * <p>
 * Services are called directly rather than through HTTP because the subject
 * here is transactional behaviour, not request mapping. {@link BookingApiIT}
 * covers the HTTP layer.
 */
class BookingLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private BookingPaymentService bookingPaymentService;

    @Autowired
    private BookingCancellationService bookingCancellationService;

    @Autowired
    private BookingQueryService bookingQueryService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Nested
    @DisplayName("holding seats")
    class Holding {

        @Test
        void aHoldPricesTheSeatsAndFreezesTheTotal() {
            TestScenario scenario = testData.createBookableShow();

            BookingResponse booking = hold(scenario, scenario.premiumSeatIds());

            assertThat(booking.status()).isEqualTo(BookingStatus.HOLD_CREATED);
            assertThat(booking.seatCount()).isEqualTo(2);
            assertThat(booking.subtotalAmount())
                    .isEqualByComparingTo(TestDataFactory.PREMIUM_WEEKDAY_PRICE.multiply(java.math.BigDecimal.TWO));
            assertThat(booking.discountAmount()).isEqualByComparingTo("0.00");
            assertThat(booking.totalAmount()).isEqualByComparingTo(booking.subtotalAmount());
            assertThat(booking.holdExpiresAt())
                    .isEqualTo(Instant.now(clock).plus(Duration.ofMinutes(5)));
            assertThat(booking.bookingReference()).startsWith("MTB-");
        }

        @Test
        void heldSeatsAreMarkedHeldInInventory() {
            TestScenario scenario = testData.createBookableShow();

            hold(scenario, scenario.premiumSeatIds());

            assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                    .containsOnly(ShowSeatStatus.HELD.name());
            assertThat(seatStatuses(scenario.showId(), scenario.regularSeatIds()))
                    .containsOnly(ShowSeatStatus.AVAILABLE.name());
        }

        /**
         * Prices are snapshotted per seat, including the day type that
         * justified them - which is what protects the row from later changes
         * to {@code pricing_rules}.
         */
        @Test
        void eachSeatCarriesItsOwnPriceSnapshot() {
            TestScenario scenario = testData.createBookableShow();

            BookingResponse booking = hold(scenario, scenario.seatIds());

            assertThat(booking.seats()).hasSize(6);
            assertThat(booking.seats())
                    .filteredOn(seat -> seat.category().name().equals("PREMIUM"))
                    .allSatisfy(seat -> assertThat(seat.price())
                            .isEqualByComparingTo(TestDataFactory.PREMIUM_WEEKDAY_PRICE));
            assertThat(booking.seats())
                    .filteredOn(seat -> seat.category().name().equals("REGULAR"))
                    .allSatisfy(seat -> assertThat(seat.price())
                            .isEqualByComparingTo(TestDataFactory.REGULAR_WEEKDAY_PRICE));
        }

        @Test
        void aShowThatHasAlreadyStartedCannotBeBooked() {
            TestScenario scenario = testData.createBookableShow(Instant.now(clock).minus(Duration.ofMinutes(1)));

            assertThatThrownBy(() -> hold(scenario, List.of(scenario.firstSeat())))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.SHOW_ALREADY_STARTED);
        }

        @Test
        void seatsFromAnotherScreenAreRejected() {
            TestScenario first = testData.createBookableShow();
            TestScenario second = testData.createBookableShow();

            assertThatThrownBy(() -> seatHoldService.createHold(new CreateHoldCommand(
                    first.showId(), List.of(second.firstSeat()), null, first.customerId())))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.SEAT_NOT_IN_SHOW);
        }

        @Test
        void requestingTheSameSeatTwiceIsRejected() {
            TestScenario scenario = testData.createBookableShow();
            UUID seat = scenario.firstSeat();

            assertThatThrownBy(() -> seatHoldService.createHold(new CreateHoldCommand(
                    scenario.showId(), List.of(seat, seat), null, scenario.customerId())))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_SEAT_IN_REQUEST);
        }
    }

    @Nested
    @DisplayName("payment")
    class Payment {

        @Test
        void aSuccessfulPaymentConfirmsTheBookingAndBooksTheSeats() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());

            BookingResponse confirmed = pay(scenario, held, PaymentMethodToken.SUCCESS.token(), "key-1");

            assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(confirmed.confirmedAt()).isEqualTo(Instant.now(clock));
            assertThat(confirmed.holdExpiresAt()).isNull();
            assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                    .containsOnly(ShowSeatStatus.BOOKED.name());
            assertThat(paymentRepository.findAllByBookingIdOrderByCreatedAtDesc(held.bookingId()))
                    .singleElement()
                    .satisfies(payment -> {
                        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
                        assertThat(payment.getGatewayReference()).startsWith("ch_");
                        assertThat(payment.getAmount()).isEqualByComparingTo(held.totalAmount());
                    });
        }

        @Test
        void confirmationQueuesANotificationWithinTheSameTransaction() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());

            pay(scenario, held, PaymentMethodToken.SUCCESS.token(), "key-notify");

            assertThat(notificationTypesFor(held.bookingId()))
                    .contains(NotificationType.BOOKING_CONFIRMED.name());
        }

        /**
         * The reason idempotency keys exist: a client that times out and
         * retries must not be charged twice.
         */
        @Test
        void retryingWithTheSameIdempotencyKeyReplaysTheOriginalOutcome() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());

            BookingResponse first = pay(scenario, held, PaymentMethodToken.SUCCESS.token(), "idem-same");
            BookingResponse replay = pay(scenario, held, PaymentMethodToken.SUCCESS.token(), "idem-same");

            assertThat(replay.bookingId()).isEqualTo(first.bookingId());
            assertThat(replay.status()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(paymentRepository.findAllByBookingIdOrderByCreatedAtDesc(held.bookingId()))
                    .as("a replay must not create a second payment")
                    .hasSize(1);
        }

        @Test
        void aConfirmedBookingCannotBePaidForAgainUnderANewKey() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());
            pay(scenario, held, PaymentMethodToken.SUCCESS.token(), "idem-first");

            assertThatThrownBy(() -> pay(scenario, held, PaymentMethodToken.SUCCESS.token(), "idem-second"))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        /** A decline must give the seats back, not strand them. */
        @Test
        void aDeclinedPaymentReleasesTheSeatsAndEndsTheBooking() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());

            assertThatThrownBy(() -> pay(scenario, held, PaymentMethodToken.FAILURE.token(), "idem-decline"))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.PAYMENT_FAILED);

            assertThat(bookingStatus(held.bookingId())).isEqualTo(BookingStatus.PAYMENT_FAILED.name());
            assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                    .as("a declined payment must return the seats to the pool")
                    .containsOnly(ShowSeatStatus.AVAILABLE.name());
            assertThat(paymentRepository.findAllByBookingIdOrderByCreatedAtDesc(held.bookingId()))
                    .singleElement()
                    .satisfies(payment -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED));
        }

        @Test
        void seatsReleasedByAFailedPaymentCanBeBookedByAnotherCustomer() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());
            assertThatThrownBy(() -> pay(scenario, held, PaymentMethodToken.FAILURE.token(), "idem-x"))
                    .isInstanceOf(DomainException.class);

            BookingResponse second = seatHoldService.createHold(new CreateHoldCommand(
                    scenario.showId(), scenario.premiumSeatIds(), null, scenario.otherCustomerId()));

            assertThat(second.status()).isEqualTo(BookingStatus.HOLD_CREATED);
        }

        /**
         * A transport failure is not a decline. The outcome is unknown, so the
         * payment stays PENDING for reconciliation and the seats stay held
         * until the hold lapses naturally - claiming "not charged" would be a
         * guess.
         */
        @Test
        void aGatewayTransportFailureLeavesThePaymentPendingAndTheSeatsHeld() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());

            assertThatThrownBy(() -> pay(scenario, held, PaymentMethodToken.ERROR.token(), "idem-error"))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR);

            assertThat(paymentRepository.findAllByBookingIdOrderByCreatedAtDesc(held.bookingId()))
                    .singleElement()
                    .satisfies(payment -> {
                        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
                        assertThat(payment.getFailureReason()).isNotBlank();
                    });
            assertThat(bookingStatus(held.bookingId())).isEqualTo(BookingStatus.PAYMENT_PENDING.name());
            assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                    .containsOnly(ShowSeatStatus.HELD.name());
        }

        @Test
        void anotherCustomerCannotPayForYourBooking() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());

            assertThatThrownBy(() -> bookingPaymentService.pay(
                    held.bookingId(),
                    scenario.otherCustomerId(),
                    new PayBookingRequest(PaymentMethodToken.SUCCESS.token(), "idem-thief")))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_BOOKING_ACCESS);
        }
    }

    @Nested
    @DisplayName("cancellation and refunds")
    class Cancellation {

        @Test
        void cancellingWellBeforeTheShowRefundsInFull() {
            TestScenario scenario = testData.createBookableShow(Instant.now(clock).plus(Duration.ofHours(48)));
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());
            BookingResponse confirmed = pay(scenario, held, PaymentMethodToken.SUCCESS.token(), "idem-refund-full");

            CancellationResponse cancellation = bookingCancellationService.cancel(
                    confirmed.bookingId(), scenario.customerId());

            assertThat(cancellation.booking().status()).isEqualTo(BookingStatus.REFUNDED);
            assertThat(cancellation.refund().refundPercentage()).isEqualByComparingTo("100.00");
            assertThat(cancellation.refund().refundAmount()).isEqualByComparingTo(confirmed.totalAmount());
            assertThat(cancellation.refund().status()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(cancellation.refund().gatewayReference()).startsWith("rf_");
        }

        @Test
        void cancellingInsideTheHalfRefundBandRefundsHalf() {
            TestScenario scenario = testData.createBookableShow(Instant.now(clock).plus(Duration.ofHours(18)));
            BookingResponse confirmed = holdAndPay(scenario, "idem-refund-half");

            CancellationResponse cancellation = bookingCancellationService.cancel(
                    confirmed.bookingId(), scenario.customerId());

            assertThat(cancellation.refund().refundPercentage()).isEqualByComparingTo("50.00");
            assertThat(cancellation.refund().refundAmount())
                    .isEqualByComparingTo(confirmed.totalAmount().divide(java.math.BigDecimal.TWO));
            assertThat(cancellation.booking().status()).isEqualTo(BookingStatus.REFUNDED);
        }

        /**
         * Inside the no-refund band the cancellation still succeeds - the
         * customer is allowed to give up their seat - but nothing is returned,
         * and the booking lands in CANCELLED rather than REFUNDED.
         */
        @Test
        void cancellingCloseToTheShowIsAllowedButRefundsNothing() {
            TestScenario scenario = testData.createBookableShow(Instant.now(clock).plus(Duration.ofHours(6)));
            BookingResponse confirmed = holdAndPay(scenario, "idem-refund-none");

            CancellationResponse cancellation = bookingCancellationService.cancel(
                    confirmed.bookingId(), scenario.customerId());

            assertThat(cancellation.booking().status()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(cancellation.refund().refundAmount()).isEqualByComparingTo("0.00");
            assertThat(cancellation.refund().status()).isEqualTo(RefundStatus.COMPLETED);
            assertThat(cancellation.refund().gatewayReference())
                    .as("no money moved, so no gateway reference")
                    .isNull();
        }

        /** A theater's own policy wins over the platform default. */
        @Test
        void aTheaterPolicyOverridesTheDefaultLadder() {
            TestScenario scenario = testData.createBookableShow(Instant.now(clock).plus(Duration.ofHours(6)));
            testData.createTheaterRefundPolicy(scenario.theaterId(), 2);
            BookingResponse confirmed = holdAndPay(scenario, "idem-theater-policy");

            CancellationResponse cancellation = bookingCancellationService.cancel(
                    confirmed.bookingId(), scenario.customerId());

            assertThat(cancellation.refund().refundPercentage())
                    .as("6h out falls in the theater's 100%% band, not the default 0%% band")
                    .isEqualByComparingTo("100.00");
        }

        @Test
        void cancellingReleasesTheSeatsForSomebodyElse() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse confirmed = holdAndPay(scenario, "idem-release");

            bookingCancellationService.cancel(confirmed.bookingId(), scenario.customerId());

            assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                    .containsOnly(ShowSeatStatus.AVAILABLE.name());
            assertThat(seatHoldService.createHold(new CreateHoldCommand(
                    scenario.showId(), scenario.premiumSeatIds(), null, scenario.otherCustomerId()))
                    .status()).isEqualTo(BookingStatus.HOLD_CREATED);
        }

        /** Backed by the unique constraint on {@code refunds.booking_id}. */
        @Test
        void cancellingTwiceReplaysTheOriginalRefund() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse confirmed = holdAndPay(scenario, "idem-double-cancel");

            CancellationResponse first = bookingCancellationService.cancel(
                    confirmed.bookingId(), scenario.customerId());
            CancellationResponse second = bookingCancellationService.cancel(
                    confirmed.bookingId(), scenario.customerId());

            assertThat(second.refund().refundId()).isEqualTo(first.refund().refundId());
            assertThat(second.booking().status()).isEqualTo(BookingStatus.REFUNDED);
            assertThat(refundRepository.findAll()).hasSize(1);
        }

        @Test
        void anUnpaidHoldCanBeCancelledWithNoRefund() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario, scenario.premiumSeatIds());

            CancellationResponse cancellation = bookingCancellationService.cancel(
                    held.bookingId(), scenario.customerId());

            assertThat(cancellation.booking().status()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(cancellation.refund()).isNull();
            assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                    .containsOnly(ShowSeatStatus.AVAILABLE.name());
        }

        @Test
        void aShowThatHasStartedCannotBeCancelled() {
            TestScenario scenario = testData.createBookableShow(Instant.now(clock).plus(Duration.ofHours(2)));
            BookingResponse confirmed = holdAndPay(scenario, "idem-too-late");

            clock.advance(Duration.ofHours(3));

            assertThatThrownBy(() -> bookingCancellationService.cancel(
                    confirmed.bookingId(), scenario.customerId()))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.BOOKING_NOT_CANCELLABLE);
        }

        @Test
        void anotherCustomerCannotCancelYourBooking() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse confirmed = holdAndPay(scenario, "idem-not-yours");

            assertThatThrownBy(() -> bookingCancellationService.cancel(
                    confirmed.bookingId(), scenario.otherCustomerId()))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_BOOKING_ACCESS);
        }
    }

    @Nested
    @DisplayName("booking history")
    class History {

        @Test
        void customersSeeOnlyTheirOwnBookings() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse mine = hold(scenario, scenario.premiumSeatIds());

            assertThatThrownBy(() -> bookingQueryService.getBooking(
                    mine.bookingId(), scenario.otherCustomerId()))
                    .isInstanceOf(DomainException.class)
                    .extracting(ex -> ((DomainException) ex).errorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_BOOKING_ACCESS);
        }

        @Test
        void historyIncludesTheSeatsOfEveryBooking() {
            TestScenario scenario = testData.createBookableShow();
            hold(scenario, scenario.premiumSeatIds());
            seatHoldService.createHold(new CreateHoldCommand(
                    scenario.showId(), scenario.regularSeatIds().subList(0, 2), null, scenario.customerId()));

            var history = bookingQueryService.getHistory(
                    scenario.customerId(), org.springframework.data.domain.PageRequest.of(0, 10));

            assertThat(history.totalElements()).isEqualTo(2);
            assertThat(history.content()).allSatisfy(booking ->
                    assertThat(booking.seats()).hasSize(2));
        }
    }

    // -----------------------------------------------------------------------

    private BookingResponse hold(TestScenario scenario, List<UUID> seatIds) {
        return seatHoldService.createHold(
                new CreateHoldCommand(scenario.showId(), seatIds, null, scenario.customerId()));
    }

    private BookingResponse pay(TestScenario scenario, BookingResponse booking, String token, String key) {
        return bookingPaymentService.pay(
                booking.bookingId(), scenario.customerId(), new PayBookingRequest(token, key));
    }

    private BookingResponse holdAndPay(TestScenario scenario, String idempotencyKey) {
        BookingResponse held = hold(scenario, scenario.premiumSeatIds());
        return pay(scenario, held, PaymentMethodToken.SUCCESS.token(), idempotencyKey);
    }

    private List<String> seatStatuses(UUID showId, List<UUID> seatIds) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ANY (?)",
                String.class, showId, seatIds.toArray(UUID[]::new));
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private List<String> notificationTypesFor(UUID bookingId) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM notifications WHERE booking_id = ?", String.class, bookingId);
    }
}
