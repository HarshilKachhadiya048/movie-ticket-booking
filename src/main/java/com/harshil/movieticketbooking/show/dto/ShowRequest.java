package com.harshil.movieticketbooking.show.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Schedule a show.
 *
 * @param startsAt absolute UTC instant. Must be in the future - scheduling a
 *                 show into the past would create inventory nobody can book.
 * @param endsAt   optional. When omitted the service derives it from the
 *                 movie's runtime, which is the common case and avoids the
 *                 admin having to do the arithmetic. Supplying it explicitly
 *                 allows for trailers, intervals and turnaround time.
 */
public record ShowRequest(
        @NotNull(message = "screenId is required") UUID screenId,
        @NotNull(message = "movieId is required") UUID movieId,
        @NotNull(message = "startsAt is required")
        @Future(message = "startsAt must be in the future") Instant startsAt,
        Instant endsAt) {
}
