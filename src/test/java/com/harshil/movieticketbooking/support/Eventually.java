package com.harshil.movieticketbooking.support;

import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Waits for an asynchronous side effect, without pulling in another library.
 * <p>
 * Used only where the behaviour under test genuinely is asynchronous -
 * notification delivery on the executor. Everything else is asserted
 * synchronously, because a test that polls for something that should have
 * happened already is a test that can pass for the wrong reason.
 */
public final class Eventually {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private Eventually() {
    }

    public static void assertThat(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        if (!condition.getAsBoolean()) {
            fail("Timed out after %s waiting for: %s".formatted(timeout, description));
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", ex);
        }
    }
}
