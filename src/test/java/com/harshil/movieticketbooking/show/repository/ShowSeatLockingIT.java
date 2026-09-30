package com.harshil.movieticketbooking.show.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.TestScenario;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Direct evidence that {@code @Lock(PESSIMISTIC_WRITE)} produces real
 * {@code SELECT ... FOR UPDATE} behaviour against PostgreSQL.
 * <p>
 * {@link com.harshil.movieticketbooking.booking.SeatHoldConcurrencyIT} proves
 * the outcome - one winner. This proves the <em>mechanism</em>, which is worth
 * separating: if the annotation were dropped or Hibernate stopped emitting the
 * clause, the booking test would still pass through the optimistic version
 * check, just with uglier errors. This test fails immediately and points at
 * the cause.
 */
class ShowSeatLockingIT extends AbstractIntegrationTest {

    private static final Duration LOCK_HOLD_TIME = Duration.ofMillis(600);

    @Autowired
    private ShowSeatRepository showSeatRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Two transactions ask for the same row. The second must wait for the
     * first to commit, so its wall-clock wait is at least as long as the first
     * transaction held the lock.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a second transaction blocks until the first releases the row lock")
    void aLockedRowBlocksTheNextReader() throws Exception {
        TestScenario scenario = testData.createBookableShow();
        UUID seatId = scenario.firstSeat();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        AtomicLong blockedForMillis = new AtomicLong();

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    showSeatRepository.lockByShowAndSeatIds(scenario.showId(), List.of(seatId));
                    lockAcquired.countDown();
                    sleep(LOCK_HOLD_TIME);
                }));

        assertThat(lockAcquired.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> contender = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    long startedAt = System.nanoTime();
                    showSeatRepository.lockByShowAndSeatIds(scenario.showId(), List.of(seatId));
                    blockedForMillis.set((System.nanoTime() - startedAt) / 1_000_000);
                }));

        CompletableFuture.allOf(holder, contender).get(30, TimeUnit.SECONDS);

        assertThat(blockedForMillis.get())
                .as("the second transaction should have waited for the first to commit")
                .isGreaterThanOrEqualTo(LOCK_HOLD_TIME.toMillis() / 2);
    }

    /**
     * The lock must be narrow. Locking a different row of the same show has to
     * proceed immediately - if it did not, every booking for a popular show
     * would serialise behind every other.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("locking one seat does not block a different seat of the same show")
    void differentRowsDoNotBlockEachOther() throws Exception {
        TestScenario scenario = testData.createBookableShow();
        UUID firstSeat = scenario.seatIds().get(0);
        UUID secondSeat = scenario.seatIds().get(1);

        CountDownLatch lockAcquired = new CountDownLatch(1);
        AtomicLong blockedForMillis = new AtomicLong();

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    showSeatRepository.lockByShowAndSeatIds(scenario.showId(), List.of(firstSeat));
                    lockAcquired.countDown();
                    sleep(LOCK_HOLD_TIME);
                }));

        assertThat(lockAcquired.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> other = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    long startedAt = System.nanoTime();
                    showSeatRepository.lockByShowAndSeatIds(scenario.showId(), List.of(secondSeat));
                    blockedForMillis.set((System.nanoTime() - startedAt) / 1_000_000);
                }));

        CompletableFuture.allOf(holder, other).get(30, TimeUnit.SECONDS);

        assertThat(blockedForMillis.get())
                .as("an unrelated seat must not wait behind another seat's lock")
                .isLessThan(LOCK_HOLD_TIME.toMillis() / 2);
    }

    /**
     * The query must return rows in {@code seat_id} order. Every transaction
     * then walks contended rows in the same sequence, which is what keeps
     * overlapping multi-seat requests from deadlocking.
     */
    @Test
    void lockedRowsComeBackInDeterministicOrder() {
        TestScenario scenario = testData.createBookableShow();
        List<UUID> shuffled = new java.util.ArrayList<>(scenario.seatIds());
        java.util.Collections.shuffle(shuffled);

        List<UUID> locked = transactionTemplate.execute(status ->
                showSeatRepository.lockByShowAndSeatIds(scenario.showId(), shuffled)
                        .stream()
                        .map(showSeat -> showSeat.getSeat().getId())
                        .toList());

        assertThat(locked).isSorted();
        assertThat(locked).containsExactlyInAnyOrderElementsOf(scenario.seatIds());
    }

    @Test
    void availabilityCountingTreatsLapsedHoldsAsFree() {
        TestScenario scenario = testData.createBookableShow();
        UUID seatId = scenario.firstSeat();
        var now = java.time.Instant.now(clock);

        transactionTemplate.executeWithoutResult(status -> {
            ShowSeat showSeat = showSeatRepository
                    .lockByShowAndSeatIds(scenario.showId(), List.of(seatId))
                    .getFirst();
            showSeat.hold(UUID.randomUUID(), scenario.customerId(), now.plus(Duration.ofMinutes(5)), now);
        });

        long whileHeld = showSeatRepository.countAvailable(
                scenario.showId(), now, ShowSeatStatus.AVAILABLE, ShowSeatStatus.HELD);
        long afterExpiry = showSeatRepository.countAvailable(
                scenario.showId(), now.plus(Duration.ofMinutes(6)),
                ShowSeatStatus.AVAILABLE, ShowSeatStatus.HELD);

        assertThat(whileHeld).isEqualTo(scenario.seatIds().size() - 1L);
        assertThat(afterExpiry)
                .as("the count a customer sees must agree with what they can actually book")
                .isEqualTo(scenario.seatIds().size());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
