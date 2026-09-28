package com.harshil.movieticketbooking.booking.repository;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * Locks a booking for the rest of the transaction.
     * <p>
     * Used by payment and cancellation so that two concurrent requests against
     * the same booking - a double-clicked "Pay", a retried cancel - are
     * serialised and the second one observes the first one's committed state
     * instead of racing it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b
            FROM Booking b
            WHERE b.id = :bookingId
            """)
    Optional<Booking> lockById(@Param("bookingId") UUID bookingId);

    /** Full detail for one booking, including its priced seats. */
    @Query("""
            SELECT DISTINCT b
            FROM Booking b
            JOIN FETCH b.user
            JOIN FETCH b.show s
            JOIN FETCH s.movie
            JOIN FETCH s.screen screen
            JOIN FETCH screen.theater theater
            JOIN FETCH theater.city
            LEFT JOIN FETCH b.seats
            LEFT JOIN FETCH b.discountCode
            WHERE b.id = :bookingId
            """)
    Optional<Booking> findDetailById(@Param("bookingId") UUID bookingId);

    /**
     * A customer's booking history.
     * <p>
     * Only to-one associations are fetch-joined here. Joining the seats
     * collection as well would force Hibernate to paginate in memory over the
     * full result set; the seats for the page are loaded in a second query
     * instead - two queries in total, not one per booking.
     */
    @Query(value = """
            SELECT b
            FROM Booking b
            JOIN FETCH b.show s
            JOIN FETCH s.movie
            JOIN FETCH s.screen screen
            JOIN FETCH screen.theater theater
            JOIN FETCH theater.city
            WHERE b.user.id = :userId
            """,
            countQuery = """
                    SELECT COUNT(b)
                    FROM Booking b
                    WHERE b.user.id = :userId
                    """)
    Page<Booking> findHistoryForUser(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Bookings whose hold has lapsed without payment. Read unlocked; the
     * sweeper re-reads each one under a lock before touching it.
     */
    @Query("""
            SELECT b.id
            FROM Booking b
            WHERE b.status IN :statuses
              AND b.holdExpiresAt IS NOT NULL
              AND b.holdExpiresAt <= :now
            ORDER BY b.holdExpiresAt ASC
            """)
    List<UUID> findExpiredHoldBookingIds(
            @Param("now") Instant now,
            @Param("statuses") Collection<BookingStatus> statuses,
            Limit limit);

    /**
     * Claims bookings whose show is close enough to warrant a reminder.
     * <p>
     * Native, because {@code FOR UPDATE ... SKIP LOCKED} has no JPQL
     * equivalent. Skipping locked rows means a second application instance
     * running the same sweep picks up a different batch instead of blocking on
     * the first one's rows, so the scheduler scales horizontally without a
     * leader election. {@code FOR UPDATE OF b} locks only the booking rows, not
     * the joined shows.
     * <p>
     * This is a throughput optimisation, not the correctness mechanism:
     * reminders are made exactly-once by the unique {@code dedupe_key} on
     * {@code notifications}.
     */
    @Query(value = """
            SELECT b.id
            FROM bookings b
            JOIN shows s ON s.id = b.show_id
            WHERE b.status = 'CONFIRMED'
              AND b.reminder_sent_at IS NULL
              AND s.starts_at > :now
              AND s.starts_at <= :threshold
            ORDER BY s.starts_at ASC
            LIMIT :batchSize
            FOR UPDATE OF b SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> claimBookingsDueForReminder(
            @Param("now") Instant now,
            @Param("threshold") Instant threshold,
            @Param("batchSize") int batchSize);

    boolean existsByShowIdAndStatusIn(UUID showId, Collection<BookingStatus> statuses);

    boolean existsByBookingReference(String bookingReference);
}
