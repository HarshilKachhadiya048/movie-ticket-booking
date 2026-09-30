package com.harshil.movieticketbooking.booking.scheduler;

import com.harshil.movieticketbooking.booking.service.ExpiredHoldSweeper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link ExpiredHoldSweeper} on a fixed delay.
 * <p>
 * Deliberately trivial. All of the interesting behaviour - and all of the
 * locking - lives in the sweeper, which the integration tests call directly
 * rather than waiting for a tick. Turning this off, as the test profile does,
 * cannot cause a seat to be lost: expired holds are re-claimable regardless,
 * because expiry is evaluated under the lock at hold time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "booking.hold-sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class ExpiredHoldScheduler {

    private final ExpiredHoldSweeper expiredHoldSweeper;

    @Scheduled(
            fixedDelayString = "${booking.hold-sweeper.interval:PT30S}",
            initialDelayString = "${booking.hold-sweeper.interval:PT30S}")
    public void sweepExpiredHolds() {
        try {
            expiredHoldSweeper.sweep();
        } catch (RuntimeException ex) {
            log.error("Expired hold sweep failed; will retry on the next tick", ex);
        }
    }
}
