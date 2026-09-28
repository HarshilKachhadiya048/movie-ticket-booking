package com.harshil.movieticketbooking.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ids of a complete, bookable world built by {@link TestDataFactory}.
 * <p>
 * Ids rather than entities: tests run outside a persistence context and most
 * of them go on to load fresh state through a service or repository anyway.
 * Handing back detached entities would invite lazy-initialisation failures
 * that have nothing to do with what is being tested.
 *
 * @param seatIds        every seat on the screen, ordered by row then number
 * @param premiumSeatIds the subset priced at the premium tier
 * @param regularSeatIds the subset priced at the regular tier
 */
public record TestScenario(
        UUID adminId,
        String adminUsername,
        UUID customerId,
        String customerUsername,
        UUID otherCustomerId,
        String otherCustomerUsername,
        UUID cityId,
        UUID movieId,
        UUID theaterId,
        UUID screenId,
        UUID showId,
        Instant showStartsAt,
        List<UUID> seatIds,
        List<UUID> premiumSeatIds,
        List<UUID> regularSeatIds,
        UUID refundPolicyId) {

    public UUID firstSeat() {
        return seatIds.getFirst();
    }

    public List<UUID> firstSeats(int count) {
        return seatIds.subList(0, count);
    }
}
