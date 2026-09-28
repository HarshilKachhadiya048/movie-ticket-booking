package com.harshil.movieticketbooking.booking.service;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.booking.domain.BookingSeat;
import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.repository.BookingRepository;
import com.harshil.movieticketbooking.booking.repository.BookingSeatRepository;
import com.harshil.movieticketbooking.common.api.PageResponse;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side access to bookings.
 * <p>
 * <b>Ownership is enforced here, in the domain layer.</b> URL-level security
 * can say "only customers may call this endpoint", but it cannot say "only
 * <em>your</em> bookings", because that depends on the row. Checking it here
 * also lets the failure be reported as
 * {@link ErrorCode#UNAUTHORIZED_BOOKING_ACCESS} rather than an unexplained
 * 403.
 * <p>
 * A booking belonging to somebody else reports 403, not 404. Both are
 * defensible; 403 is chosen because the caller already had to authenticate and
 * guess a v7 UUID, so hiding existence buys nothing that the id space does not
 * already provide.
 */
@Service
@RequiredArgsConstructor
public class BookingQueryService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;

    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID bookingId, UUID userId) {
        Booking booking = bookingRepository.findDetailById(bookingId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.BOOKING_NOT_FOUND, "Booking %s does not exist".formatted(bookingId)));
        if (!booking.isOwnedBy(userId)) {
            throw new DomainException(
                    ErrorCode.UNAUTHORIZED_BOOKING_ACCESS,
                    "Booking %s belongs to another customer".formatted(bookingId));
        }
        return BookingResponse.from(booking);
    }

    /**
     * A customer's booking history, newest first.
     * <p>
     * Two queries in total regardless of page size: one page of bookings with
     * their to-one associations, then the seats for that page in bulk. Letting
     * each booking lazily load its own seats would be N+1; fetch-joining the
     * collection into the paged query would force Hibernate to paginate the
     * whole result set in memory.
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getHistory(UUID userId, Pageable pageable) {
        Page<Booking> page = bookingRepository.findHistoryForUser(userId, pageable);
        if (page.isEmpty()) {
            return PageResponse.of(page.map(BookingResponse::from));
        }

        List<UUID> bookingIds = page.getContent().stream().map(Booking::getId).toList();
        Map<UUID, List<BookingSeat>> seatsByBooking = bookingSeatRepository.findAllByBookingIdIn(bookingIds)
                .stream()
                .collect(Collectors.groupingBy(bookingSeat -> bookingSeat.getBooking().getId()));

        return PageResponse.of(page.map(booking ->
                BookingResponse.from(booking, seatsByBooking.getOrDefault(booking.getId(), List.of()))));
    }
}
