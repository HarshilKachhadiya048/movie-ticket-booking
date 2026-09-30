package com.harshil.movieticketbooking.notification.repository;

import com.harshil.movieticketbooking.notification.domain.Notification;
import com.harshil.movieticketbooking.notification.domain.NotificationStatus;
import com.harshil.movieticketbooking.notification.domain.NotificationType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * The idempotency lookup. Paired with the unique index on
     * {@code dedupe_key}, it turns "send this notification" into an operation
     * that can be attempted any number of times and still produce one message.
     */
    Optional<Notification> findByDedupeKey(String dedupeKey);

    boolean existsByDedupeKey(String dedupeKey);

    List<Notification> findAllByBookingIdOrderByCreatedAtAsc(UUID bookingId);

    List<Notification> findAllByUserIdAndTypeOrderByCreatedAtDesc(UUID userId, NotificationType type);

    long countByStatus(NotificationStatus status);
}
