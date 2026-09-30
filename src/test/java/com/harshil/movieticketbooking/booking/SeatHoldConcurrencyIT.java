package com.harshil.movieticketbooking.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshil.movieticketbooking.booking.service.CreateHoldCommand;
import com.harshil.movieticketbooking.booking.service.SeatHoldService;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.TestScenario;
import com.harshil.movieticketbooking.user.domain.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The correctness test this whole system exists to pass.
 *
 * <h2>What is being proved</h2>
 * That two customers can never both be told they have the same seat for the
 * same show - not "usually", and not "as long as the scheduler ran", but as an
 * invariant enforced by PostgreSQL row locks.
 *
 * <h2>Why this runs against real PostgreSQL</h2>
 * The guarantee <em>is</em> {@code SELECT ... FOR UPDATE} blocking semantics.
 * H2's PostgreSQL compatibility mode does not reproduce them, so a green run
 * on H2 would prove nothing at all. Testcontainers gives the same major
 * version the application deploys against.
 *
 * <h2>Why the threads are released by a latch</h2>
 * Simply submitting twenty tasks would let the first finish before the last
 * started, and the test would pass without any contention ever occurring.
 * Every thread does its setup, reports ready, and then blocks on a shared
 * latch, so the contended statement is reached by all of them at once.
 */
class SeatHoldConcurrencyIT extends AbstractIntegrationTest {

    private static final int CONCURRENT_CUSTOMERS = 20;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("20 customers race for one seat: exactly one wins, the rest are rejected cleanly")
    void exactlyOneCustomerWinsASingleContestedSeat() throws Exception {
        TestScenario scenario = testData.createBookableShow();
        UUID contestedSeat = scenario.firstSeat();
        List<UUID> customerIds = createCustomers(CONCURRENT_CUSTOMERS);

        RaceResult result = race(customerIds, customerId -> seatHoldService.createHold(
                new CreateHoldCommand(scenario.showId(), List.of(contestedSeat), null, customerId)));

        // --- Exactly one winner -------------------------------------------
        assertThat(result.successes())
                .as("exactly one hold may succeed")
                .isEqualTo(1);
        assertThat(result.failures())
                .as("every other attempt must fail")
                .isEqualTo(CONCURRENT_CUSTOMERS - 1);

        // --- The losers failed for the right reason, not by accident -------
        assertThat(result.unexpectedErrors())
                .as("no attempt may fail with an unexpected error: %s", result.unexpectedErrors())
                .isEmpty();
        assertThat(result.errorCodes())
                .as("losers must be told the seat is taken")
                .containsOnlyKeys(ErrorCode.SEAT_ALREADY_HELD);
        assertThat(result.errorCodes().get(ErrorCode.SEAT_ALREADY_HELD))
                .isEqualTo(CONCURRENT_CUSTOMERS - 1);

        // --- The database agrees -------------------------------------------
        assertThat(showSeatStatus(scenario.showId(), contestedSeat)).isEqualTo(ShowSeatStatus.HELD.name());
        assertThat(activeAllocationsFor(scenario.showId(), contestedSeat))
                .as("no duplicate allocation rows may exist for the seat")
                .isEqualTo(1);
        assertThat(activeBookingsFor(scenario.showId()))
                .as("only the winning booking may survive")
                .isEqualTo(1);
        assertThat(distinctHoldTokensFor(scenario.showId(), contestedSeat))
                .as("the seat must be held under exactly one token")
                .isEqualTo(1);
    }

    /**
     * The harder case. Every customer asks for two of the three available
     * seats, so the requests overlap partially rather than colliding head-on.
     * <p>
     * This is where inconsistent lock ordering would show up as a deadlock,
     * and where a partially-applied hold would show up as a seat allocated to
     * a booking that was rejected. Holds are all-or-nothing, so the number of
     * held seats must be an exact multiple of two.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("overlapping multi-seat requests never partially allocate and never deadlock")
    void overlappingMultiSeatRequestsAreAllOrNothing() throws Exception {
        TestScenario scenario = testData.createBookableShow();
        List<UUID> pool = scenario.firstSeats(3);
        List<UUID> customerIds = createCustomers(CONCURRENT_CUSTOMERS);

        // Each customer wants a different overlapping pair, and deliberately
        // asks for them in a different order, so any reliance on the caller's
        // ordering would surface here.
        List<List<UUID>> requestedPairs = List.of(
                List.of(pool.get(0), pool.get(1)),
                List.of(pool.get(1), pool.get(0)),
                List.of(pool.get(1), pool.get(2)),
                List.of(pool.get(2), pool.get(1)),
                List.of(pool.get(0), pool.get(2)),
                List.of(pool.get(2), pool.get(0)));

        AtomicInteger index = new AtomicInteger();
        RaceResult result = race(customerIds, customerId -> {
            List<UUID> seats = requestedPairs.get(index.getAndIncrement() % requestedPairs.size());
            seatHoldService.createHold(new CreateHoldCommand(scenario.showId(), seats, null, customerId));
        });

        assertThat(result.unexpectedErrors())
                .as("a deadlock or lock timeout would appear here: %s", result.unexpectedErrors())
                .isEmpty();
        assertThat(result.successes())
                .as("three seats can satisfy at most one two-seat request")
                .isEqualTo(1);

        long heldSeats = heldSeatCount(scenario.showId());
        assertThat(heldSeats)
                .as("holds are all-or-nothing, so held seats must be a multiple of the request size")
                .isEqualTo(2);
        assertThat(activeBookingSeatRows(scenario.showId()))
                .as("allocation rows must match the seats actually held")
                .isEqualTo(heldSeats);
    }

    /**
     * The other half of the guarantee: locking must be narrow. If the hold
     * path locked the show, the screen or the seat table rather than the
     * individual inventory rows, these non-overlapping requests would
     * serialise against each other and some would fail.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("concurrent holds on different seats of the same show all succeed")
    void independentSeatsDoNotContend() throws Exception {
        TestScenario scenario = testData.createBookableShow();
        List<UUID> seats = scenario.seatIds();
        List<UUID> customerIds = createCustomers(seats.size());

        AtomicInteger index = new AtomicInteger();
        RaceResult result = race(customerIds, customerId -> seatHoldService.createHold(
                new CreateHoldCommand(
                        scenario.showId(), List.of(seats.get(index.getAndIncrement())), null, customerId)));

        assertThat(result.unexpectedErrors()).isEmpty();
        assertThat(result.successes())
                .as("distinct seats must not contend with each other")
                .isEqualTo(seats.size());
        assertThat(heldSeatCount(scenario.showId())).isEqualTo(seats.size());
    }

    // -----------------------------------------------------------------------
    // Race harness
    // -----------------------------------------------------------------------

    /**
     * Runs {@code attempt} once per customer, with every thread released from
     * a single starting gate.
     * <p>
     * Virtual threads (Java 21) rather than a fixed pool: each task spends
     * most of its life blocked on a database row lock, which is exactly the
     * workload virtual threads suit, and it removes any question of the pool
     * size quietly limiting real concurrency.
     */
    private RaceResult race(List<UUID> customerIds, HoldAttempt attempt) throws InterruptedException {
        int participants = customerIds.size();
        CountDownLatch ready = new CountDownLatch(participants);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(participants);

        AtomicInteger successes = new AtomicInteger();
        Map<ErrorCode, Integer> errorCodes = Collections.synchronizedMap(new java.util.EnumMap<>(ErrorCode.class));
        List<String> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID customerId : customerIds) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        startGate.await();
                        attempt.execute(customerId);
                        successes.incrementAndGet();
                    } catch (DomainException ex) {
                        errorCodes.merge(ex.errorCode(), 1, Integer::sum);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        unexpectedErrors.add("interrupted");
                    } catch (RuntimeException ex) {
                        unexpectedErrors.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    } finally {
                        finished.countDown();
                    }
                });
            }

            assertThat(ready.await(30, TimeUnit.SECONDS))
                    .as("all threads should reach the starting gate").isTrue();
            startGate.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("all attempts should complete; a hang here means a lock was never released").isTrue();
        }

        int failures = errorCodes.values().stream().mapToInt(Integer::intValue).sum()
                + unexpectedErrors.size();
        return new RaceResult(successes.get(), failures, Map.copyOf(errorCodes), List.copyOf(unexpectedErrors));
    }

    @FunctionalInterface
    private interface HoldAttempt {

        void execute(UUID customerId);
    }

    private record RaceResult(
            int successes,
            int failures,
            Map<ErrorCode, Integer> errorCodes,
            List<String> unexpectedErrors) {
    }

    // -----------------------------------------------------------------------
    // Assertions read straight from the database, bypassing the ORM entirely
    // -----------------------------------------------------------------------

    private List<UUID> createCustomers(int count) {
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(testData.saveUser("racer-%d-%s".formatted(i, UUID.randomUUID()), Role.CUSTOMER).getId());
        }
        return ids;
    }

    private String showSeatStatus(UUID showId, UUID seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM show_seats WHERE show_id = ? AND seat_id = ?",
                String.class, showId, seatId);
    }

    private long distinctHoldTokensFor(UUID showId, UUID seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT hold_token) FROM show_seats WHERE show_id = ? AND seat_id = ?",
                Long.class, showId, seatId);
    }

    /** Allocation rows for a seat that belong to a booking still claiming it. */
    private long activeAllocationsFor(UUID showId, UUID seatId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM booking_seats bs
                         JOIN bookings b ON b.id = bs.booking_id
                         JOIN show_seats ss ON ss.id = bs.show_seat_id
                WHERE ss.show_id = ?
                  AND ss.seat_id = ?
                  AND b.status IN ('HOLD_CREATED', 'PAYMENT_PENDING', 'CONFIRMED')
                """, Long.class, showId, seatId);
    }

    private long activeBookingsFor(UUID showId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM bookings
                WHERE show_id = ?
                  AND status IN ('HOLD_CREATED', 'PAYMENT_PENDING', 'CONFIRMED')
                """, Long.class, showId);
    }

    private long activeBookingSeatRows(UUID showId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM booking_seats bs
                         JOIN bookings b ON b.id = bs.booking_id
                WHERE b.show_id = ?
                  AND b.status IN ('HOLD_CREATED', 'PAYMENT_PENDING', 'CONFIRMED')
                """, Long.class, showId);
    }

    private long heldSeatCount(UUID showId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM show_seats WHERE show_id = ? AND status = 'HELD'",
                Long.class, showId);
    }
}
