package com.harshil.movieticketbooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.PayBookingRequest;
import com.harshil.movieticketbooking.booking.service.BookingPaymentService;
import com.harshil.movieticketbooking.booking.service.CreateHoldCommand;
import com.harshil.movieticketbooking.booking.service.ExpiredHoldSweeper;
import com.harshil.movieticketbooking.booking.service.SeatHoldService;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.discount.domain.DiscountType;
import com.harshil.movieticketbooking.discount.repository.DiscountCodeRepository;
import com.harshil.movieticketbooking.payment.gateway.PaymentMethodToken;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.TestScenario;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hold expiration, and specifically the brief's requirement that the scheduler
 * must not be the only thing that makes an expired seat bookable.
 * <p>
 * The scheduler is disabled entirely in the test profile, so every test in the
 * first group runs with <em>no</em> sweeper at all. Anything that passes here
 * passes because expiry is evaluated under the row lock at hold time.
 */
class HoldExpiryIT extends AbstractIntegrationTest {

    private static final Duration HOLD_DURATION = Duration.ofMinutes(5);
    private static final Duration PAST_EXPIRY = HOLD_DURATION.plusSeconds(1);

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private BookingPaymentService bookingPaymentService;

    @Autowired
    private ExpiredHoldSweeper expiredHoldSweeper;

    @Autowired
    private DiscountCodeRepository discountCodeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The headline requirement. The scheduler has never run, the row in the
     * database still says HELD, and the seat is nonetheless bookable - because
     * the hold transaction re-evaluates expiry while holding the lock.
     */
    @Test
    @DisplayName("an expired hold is re-bookable immediately, with the sweeper never having run")
    void expiredHoldsAreReusableWithoutTheScheduler() {
        TestScenario scenario = testData.createBookableShow();
        UUID seat = scenario.firstSeat();
        seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), List.of(seat), null, scenario.customerId()));

        clock.advance(PAST_EXPIRY);

        assertThat(rawSeatStatus(scenario.showId(), seat))
                .as("the stored row is deliberately still stale")
                .isEqualTo(ShowSeatStatus.HELD.name());

        BookingResponse second = seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), List.of(seat), null, scenario.otherCustomerId()));

        assertThat(second.status()).isEqualTo(BookingStatus.HOLD_CREATED);
        assertThat(holdTokenOf(scenario.showId(), seat)).isNotNull();
    }

    @Test
    void aLiveHoldStillBlocksOtherCustomers() {
        TestScenario scenario = testData.createBookableShow();
        UUID seat = scenario.firstSeat();
        seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), List.of(seat), null, scenario.customerId()));

        clock.advance(Duration.ofMinutes(4));

        assertThatThrownBy(() -> seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), List.of(seat), null, scenario.otherCustomerId())))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ErrorCode.SEAT_ALREADY_HELD);
    }

    @Test
    void payingAfterTheHoldLapsedIsRejectedAndTheSeatsAreFreed() {
        TestScenario scenario = testData.createBookableShow();
        BookingResponse held = seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), scenario.premiumSeatIds(), null, scenario.customerId()));

        clock.advance(PAST_EXPIRY);

        assertThatThrownBy(() -> bookingPaymentService.pay(
                held.bookingId(),
                scenario.customerId(),
                new PayBookingRequest(PaymentMethodToken.SUCCESS.token(), "idem-expired")))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ErrorCode.HOLD_EXPIRED);

        assertThat(bookingStatus(held.bookingId())).isEqualTo(BookingStatus.EXPIRED.name());
        assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                .containsOnly(ShowSeatStatus.AVAILABLE.name());
    }

    // -----------------------------------------------------------------------
    // The sweeper: tidying up, never the mechanism a guarantee depends on
    // -----------------------------------------------------------------------

    @Test
    void theSweeperExpiresAbandonedBookingsAndFreesTheirSeats() {
        TestScenario scenario = testData.createBookableShow();
        BookingResponse held = seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), scenario.premiumSeatIds(), null, scenario.customerId()));

        clock.advance(PAST_EXPIRY);
        int expired = expiredHoldSweeper.sweep();

        assertThat(expired).isEqualTo(1);
        assertThat(bookingStatus(held.bookingId())).isEqualTo(BookingStatus.EXPIRED.name());
        assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                .containsOnly(ShowSeatStatus.AVAILABLE.name());
    }

    @Test
    void theSweeperLeavesLiveHoldsAlone() {
        TestScenario scenario = testData.createBookableShow();
        BookingResponse held = seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), scenario.premiumSeatIds(), null, scenario.customerId()));

        clock.advance(Duration.ofMinutes(2));
        int expired = expiredHoldSweeper.sweep();

        assertThat(expired).isZero();
        assertThat(bookingStatus(held.bookingId())).isEqualTo(BookingStatus.HOLD_CREATED.name());
        assertThat(seatStatuses(scenario.showId(), scenario.premiumSeatIds()))
                .containsOnly(ShowSeatStatus.HELD.name());
    }

    /**
     * The subtle one, and the reason release is guarded by the hold token
     * rather than by status alone.
     * <p>
     * Customer A's hold lapses, customer B takes the seat through lazy expiry,
     * and only <em>then</em> does the sweeper get around to running. If it
     * released on status, it would take the seat away from B - who is holding
     * it perfectly legitimately - and B would arrive at the cinema without a
     * seat. It must expire A's booking and leave the row alone.
     */
    @Test
    @DisplayName("a late sweep must not release a seat somebody else has since taken")
    void theSweeperDoesNotStealASeatReclaimedByAnotherCustomer() {
        TestScenario scenario = testData.createBookableShow();
        UUID seat = scenario.firstSeat();

        BookingResponse abandoned = seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), List.of(seat), null, scenario.customerId()));
        clock.advance(PAST_EXPIRY);

        BookingResponse reclaimed = seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), List.of(seat), null, scenario.otherCustomerId()));
        UUID tokenAfterReclaim = holdTokenOf(scenario.showId(), seat);

        expiredHoldSweeper.sweep();

        assertThat(bookingStatus(abandoned.bookingId()))
                .as("the abandoned booking should still be tidied up")
                .isEqualTo(BookingStatus.EXPIRED.name());
        assertThat(bookingStatus(reclaimed.bookingId()))
                .as("the new hold must be untouched")
                .isEqualTo(BookingStatus.HOLD_CREATED.name());
        assertThat(rawSeatStatus(scenario.showId(), seat)).isEqualTo(ShowSeatStatus.HELD.name());
        assertThat(holdTokenOf(scenario.showId(), seat))
                .as("the seat must still be held under the second customer's token")
                .isEqualTo(tokenAfterReclaim);
    }

    @Test
    void theSweeperIsSafeToRunRepeatedly() {
        TestScenario scenario = testData.createBookableShow();
        seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), scenario.premiumSeatIds(), null, scenario.customerId()));

        clock.advance(PAST_EXPIRY);

        assertThat(expiredHoldSweeper.sweep()).isEqualTo(1);
        assertThat(expiredHoldSweeper.sweep()).isZero();
        assertThat(expiredHoldSweeper.sweep()).isZero();
    }

    /**
     * An abandoned hold must not permanently consume a promotional code the
     * customer never actually used.
     */
    @Test
    void anExpiredHoldReturnsItsDiscountCodeToCirculation() {
        TestScenario scenario = testData.createBookableShow();
        var discount = testData.createDiscountCode(
                "SWEEP10", DiscountType.PERCENTAGE, new BigDecimal("10.00"),
                null, BigDecimal.ZERO, 1, 1);

        seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), scenario.premiumSeatIds(), "SWEEP10", scenario.customerId()));
        assertThat(usedCountOf(discount.getId())).isEqualTo(1);

        clock.advance(PAST_EXPIRY);
        expiredHoldSweeper.sweep();

        assertThat(usedCountOf(discount.getId()))
                .as("the redemption should be released so the code can be used again")
                .isZero();
        assertThat(discountUsageRows(discount.getId())).isZero();
    }

    // -----------------------------------------------------------------------

    private String rawSeatStatus(UUID showId, UUID seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                String.class, showId, seatId);
    }

    private UUID holdTokenOf(UUID showId, UUID seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT hold_token FROM show_seats WHERE show_id = ? AND seat_id = ?",
                UUID.class, showId, seatId);
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

    private int usedCountOf(UUID discountCodeId) {
        return discountCodeRepository.findById(discountCodeId).orElseThrow().getUsedCount();
    }

    private long discountUsageRows(UUID discountCodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM discount_code_usages WHERE discount_code_id = ?",
                Long.class, discountCodeId);
    }
}
