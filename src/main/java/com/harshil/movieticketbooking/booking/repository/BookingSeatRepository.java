package com.harshil.movieticketbooking.booking.repository;

import com.harshil.movieticketbooking.booking.domain.BookingSeat;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingSeatRepository extends JpaRepository<BookingSeat, UUID> {

    /**
     * Seats for a whole page of bookings in one query, so rendering booking
     * history costs two queries rather than one per booking.
     */
    @Query("""
            SELECT bs
            FROM BookingSeat bs
            WHERE bs.booking.id IN :bookingIds
            ORDER BY bs.rowLabel ASC, bs.seatNumber ASC
            """)
    List<BookingSeat> findAllByBookingIdIn(@Param("bookingIds") Collection<UUID> bookingIds);

    /** The inventory rows a booking occupies, for the release and confirm paths. */
    @Query("""
            SELECT bs.showSeat.id
            FROM BookingSeat bs
            WHERE bs.booking.id = :bookingId
            """)
    List<UUID> findShowSeatIdsByBookingId(@Param("bookingId") UUID bookingId);
}
