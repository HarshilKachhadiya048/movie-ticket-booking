package com.harshil.movieticketbooking.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} tests can move.
 * <p>
 * Hold expiry, discount validity windows and refund bands are all time
 * dependent, and the production code reads time exclusively from the injected
 * {@code Clock}. Advancing this one lets a test assert "the five-minute hold
 * has lapsed" in microseconds rather than by sleeping - which is the
 * difference between a suite that runs in seconds and one nobody runs.
 * <p>
 * {@code instant} is {@code volatile} because the concurrency test reads it
 * from many threads at once.
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock at(Instant instant) {
        return new MutableClock(instant, ZoneId.of("UTC"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration amount) {
        this.instant = this.instant.plus(amount);
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }
}
