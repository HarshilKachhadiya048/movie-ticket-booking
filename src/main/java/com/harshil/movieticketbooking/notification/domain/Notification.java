package com.harshil.movieticketbooking.notification.domain;

import com.harshil.movieticketbooking.common.domain.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A notification, persisted in the same transaction as the event that caused
 * it and delivered afterwards.
 * <p>
 * <b>Why it is stored at all.</b> Writing the row inside the business
 * transaction means "the booking is confirmed" and "a confirmation is owed"
 * commit or roll back together. Delivery then happens after commit, on a
 * bounded executor, so the customer's request never waits for it. If delivery
 * is rejected or the process dies first, the row is still there, still
 * PENDING - nothing is silently lost.
 * <p>
 * <b>{@code dedupeKey} is the idempotency mechanism.</b> "The reminder for
 * booking X" maps to exactly one key, and the unique index rejects the second
 * insert. A scheduler that runs twice, a retried dispatch, or a second
 * application instance racing the first all collapse to one notification -
 * without needing a distributed lock.
 * <p>
 * {@code status}, {@code attemptCount} and {@code availableAt} are populated
 * from the start so that adding a recovery poller later - claiming PENDING
 * rows with {@code FOR UPDATE SKIP LOCKED} - is additive rather than a
 * migration. See README "Extending to a durable outbox".
 */
@Entity
@Getter
@Builder
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Notification extends VersionedEntity {

    @Column(columnDefinition = "UUID", name = "user_id", nullable = false)
    private UUID userId;

    @Column(columnDefinition = "UUID", name = "booking_id")
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(40)", name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(columnDefinition = "VARCHAR(255)", name = "recipient", nullable = false, length = 255)
    private String recipient;

    @Column(columnDefinition = "VARCHAR(255)", name = "subject", nullable = false, length = 255)
    private String subject;

    @Column(columnDefinition = "TEXT", name = "payload", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(columnDefinition = "VARCHAR(200)", name = "dedupe_key", nullable = false, unique = true, length = 200)
    private String dedupeKey;

    @Column(columnDefinition = "INTEGER", name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(columnDefinition = "TIMESTAMPTZ", name = "sent_at")
    private Instant sentAt;

    @Column(columnDefinition = "VARCHAR(255)", name = "failure_reason", length = 255)
    private String failureReason;

    /**
     * Builds the natural key for a notification. One type per aggregate means
     * one notification, forever.
     */
    public static String dedupeKeyFor(NotificationType type, UUID aggregateId) {
        return type.name() + ":" + aggregateId;
    }

    public void markSent(Instant now) {
        this.status = NotificationStatus.SENT;
        this.attemptCount++;
        this.sentAt = now;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = NotificationStatus.FAILED;
        this.attemptCount++;
        this.failureReason = reason == null || reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    public boolean isPending() {
        return status == NotificationStatus.PENDING;
    }
}
