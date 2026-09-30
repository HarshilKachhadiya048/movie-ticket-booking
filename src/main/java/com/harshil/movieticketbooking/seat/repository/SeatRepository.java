package com.harshil.movieticketbooking.seat.repository;

import com.harshil.movieticketbooking.seat.domain.Seat;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findAllByScreenIdOrderByRowLabelAscSeatNumberAsc(UUID screenId);

    List<Seat> findAllByScreenIdAndActiveTrueOrderByRowLabelAscSeatNumberAsc(UUID screenId);

    /**
     * Loaded without a lock during seat holds. Seats are reference data: the
     * hold path only reads a seat's category to price it, and the row that is
     * actually contended is the {@code show_seats} row, which is locked.
     */
    List<Seat> findAllByIdIn(Collection<UUID> seatIds);

    long countByScreenId(UUID screenId);
}
