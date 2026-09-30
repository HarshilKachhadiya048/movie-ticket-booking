-- ===========================================================================
-- V6 - Notifications.
--
-- A notification row is inserted with status PENDING inside the same
-- transaction as the business change that caused it, then dispatched after
-- that transaction commits, on a bounded executor. The booking request never
-- waits for delivery.
--
-- dedupe_key is the idempotency guarantee. "Send the 2h reminder for booking
-- X" maps to exactly one key, so a re-running scheduler, a retried dispatch
-- or a second application instance cannot produce a duplicate notification -
-- the unique index rejects it.
--
-- This table is deliberately shaped so that durable delivery can be added
-- later without a rewrite or a data migration: status/attempt_count/
-- available_at are already here, so an outbox-style poller claiming rows with
-- FOR UPDATE SKIP LOCKED is purely additive. See README "Extending to a
-- durable outbox".
-- ===========================================================================

CREATE TABLE notifications
(
    id             UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    booking_id     UUID,
    type           VARCHAR(40)  NOT NULL,
    channel        VARCHAR(20)  NOT NULL,
    recipient      VARCHAR(255) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    dedupe_key     VARCHAR(200) NOT NULL,
    attempt_count  INTEGER      NOT NULL DEFAULT 0,
    available_at   TIMESTAMPTZ  NOT NULL,
    sent_at        TIMESTAMPTZ,
    failure_reason VARCHAR(255),
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_booking FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE CASCADE,

    CONSTRAINT uq_notifications_dedupe_key UNIQUE (dedupe_key),

    CONSTRAINT ck_notifications_type CHECK (type IN (
                                                     'BOOKING_CONFIRMED',
                                                     'BOOKING_CANCELLED',
                                                     'REFUND_COMPLETED',
                                                     'PAYMENT_FAILED',
                                                     'SHOW_REMINDER'
        )),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notifications_attempt_count_non_negative CHECK (attempt_count >= 0)
);

CREATE INDEX ix_notifications_user_created_at ON notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_booking ON notifications (booking_id);

-- Ready-to-dispatch working set. Already in the shape a SKIP LOCKED claim
-- query would want.
CREATE INDEX ix_notifications_pending ON notifications (available_at)
    WHERE status = 'PENDING';
