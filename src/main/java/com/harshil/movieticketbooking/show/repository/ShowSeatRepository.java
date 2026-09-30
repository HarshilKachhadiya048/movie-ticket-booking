package com.harshil.movieticketbooking.show.repository;

import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.domain.ShowSeatStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Seat inventory access, including the locking queries the booking flow is
 * built on.
 * <p>
 * <b>This is where double allocation is prevented.</b>
 * {@link #lockByShowAndSeatIds} carries
 * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)}, which Hibernate renders as
 * {@code SELECT ... FOR UPDATE} against PostgreSQL. The second transaction to
 * ask for the same row blocks inside the database until the first commits or
 * rolls back, and then sees the committed result. No application-level lock,
 * no cache and no external coordinator is involved, so the guarantee holds
 * across threads, connections and JVMs alike.
 * <p>
 * <b>On lock ordering.</b> The query is a single-table select - {@code show.id}
 * and {@code seat.id} resolve to the foreign key columns, so no join is
 * emitted - and it orders by {@code seat_id}. PostgreSQL places its
 * {@code LockRows} step above {@code Sort}, so rows are locked in that sorted
 * order. Every transaction therefore walks the same rows in the same sequence,
 * which is what keeps two overlapping multi-seat requests from deadlocking on
 * each other. A deadlock would still be reported as 409 rather than corrupting
 * anything, but with consistent ordering it should not arise.
 * <p>
 * Only the specific inventory rows are locked. The {@code shows} and
 * {@code seats} tables are never locked, so unrelated bookings - even for the
 * same show - proceed in parallel.
 */
@Repository
public interface ShowSeatRepository extends JpaRepository<ShowSeat, UUID> {

    /**
     * Locks the inventory rows for the given seats of a show, in a
     * deterministic order, for the remainder of the current transaction.
     * <p>
     * The returned rows must be validated <em>and</em> mutated inside that same
     * transaction: the lock is released at commit, so a check performed after
     * the transaction ends guarantees nothing.
     *
     * @return the matching rows; a shorter list than {@code seatIds} means some
     *         seats do not belong to this show and the caller must reject the
     *         request
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT ss
            FROM ShowSeat ss
            WHERE ss.show.id = :showId
              AND ss.seat.id IN :seatIds
            ORDER BY ss.seat.id
            """)
    List<ShowSeat> lockByShowAndSeatIds(
            @Param("showId") UUID showId,
            @Param("seatIds") Collection<UUID> seatIds);

    /**
     * Locks inventory rows by their own ids, used by the confirmation,
     * release and sweep paths where the rows are already known from
     * {@code booking_seats}. Ordered by id for the same deadlock-avoidance
     * reason as above.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT ss
            FROM ShowSeat ss
            WHERE ss.id IN :showSeatIds
            ORDER BY ss.id
            """)
    List<ShowSeat> lockByIds(@Param("showSeatIds") Collection<UUID> showSeatIds);

    /** Seat map for a show, with the seat eagerly joined to avoid N+1. */
    @Query("""
            SELECT ss
            FROM ShowSeat ss
            JOIN FETCH ss.seat seat
            WHERE ss.show.id = :showId
            ORDER BY seat.rowLabel ASC, seat.seatNumber ASC
            """)
    List<ShowSeat> findSeatMapByShowId(@Param("showId") UUID showId);

    /**
     * Counts seats that are bookable as of {@code now}, treating a lapsed hold
     * as available - the same rule {@code effectiveStatus} applies in Java, so
     * the seat count a customer sees agrees with what they can actually book.
     */
    @Query("""
            SELECT COUNT(ss)
            FROM ShowSeat ss
            WHERE ss.show.id = :showId
              AND (ss.status = :available
                   OR (ss.status = :held AND ss.holdExpiresAt <= :now))
            """)
    long countAvailable(
            @Param("showId") UUID showId,
            @Param("now") Instant now,
            @Param("available") ShowSeatStatus available,
            @Param("held") ShowSeatStatus held);

    /**
     * Availability for many shows in one query, so listing a theater's
     * schedule does not fan out into one count per show.
     */
    @Query("""
            SELECT ss.show.id AS showId, COUNT(ss) AS availableSeats
            FROM ShowSeat ss
            WHERE ss.show.id IN :showIds
              AND (ss.status = :available
                   OR (ss.status = :held AND ss.holdExpiresAt <= :now))
            GROUP BY ss.show.id
            """)
    List<ShowAvailabilityProjection> countAvailableByShowIds(
            @Param("showIds") Collection<UUID> showIds,
            @Param("now") Instant now,
            @Param("available") ShowSeatStatus available,
            @Param("held") ShowSeatStatus held);

    /**
     * Ids of rows still marked HELD whose hold has lapsed. Read without a lock
     * on purpose: the sweeper re-reads them under
     * {@link #lockByIds} before changing anything, so a row that was claimed in
     * the meantime is simply skipped.
     */
    @Query("""
            SELECT ss.id
            FROM ShowSeat ss
            WHERE ss.status = :held
              AND ss.holdExpiresAt <= :now
            ORDER BY ss.holdExpiresAt ASC
            """)
    List<UUID> findExpiredHoldIds(
            @Param("now") Instant now,
            @Param("held") ShowSeatStatus held,
            Limit limit);

    long countByShowId(UUID showId);

    boolean existsByShowIdAndStatus(UUID showId, ShowSeatStatus status);

    /** Row shape for {@link #countAvailableByShowIds}. */
    interface ShowAvailabilityProjection {

        UUID getShowId();

        long getAvailableSeats();
    }
}
