-- ===========================================================================
-- V4 - Bookings, the seats they own, discount redemptions and payments.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- bookings
--
-- A booking is created at hold time, already carrying its full price
-- breakdown, so the customer sees the final payable amount before paying.
-- hold_token / hold_expires_at mirror the values written onto show_seats and
-- are what the confirmation transaction re-verifies under the row lock.
-- ---------------------------------------------------------------------------
CREATE TABLE bookings
(
    id               UUID           NOT NULL,
    booking_reference VARCHAR(30)   NOT NULL,
    user_id          UUID           NOT NULL,
    show_id          UUID           NOT NULL,
    status           VARCHAR(30)    NOT NULL,
    hold_token       UUID,
    hold_expires_at  TIMESTAMPTZ,
    seat_count       INTEGER        NOT NULL,
    subtotal_amount  NUMERIC(12, 2) NOT NULL,
    discount_code_id UUID,
    discount_amount  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount     NUMERIC(12, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    confirmed_at     TIMESTAMPTZ,
    cancelled_at     TIMESTAMPTZ,
    reminder_sent_at TIMESTAMPTZ,
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_bookings PRIMARY KEY (id),
    CONSTRAINT uq_bookings_reference UNIQUE (booking_reference),
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_bookings_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT fk_bookings_discount_code FOREIGN KEY (discount_code_id) REFERENCES discount_codes (id),

    CONSTRAINT ck_bookings_status CHECK (status IN (
                                                    'HOLD_CREATED',
                                                    'PAYMENT_PENDING',
                                                    'CONFIRMED',
                                                    'PAYMENT_FAILED',
                                                    'CANCELLED',
                                                    'REFUNDED',
                                                    'EXPIRED'
        )),
    CONSTRAINT ck_bookings_seat_count_positive CHECK (seat_count > 0),
    CONSTRAINT ck_bookings_amounts_non_negative CHECK (
        subtotal_amount >= 0 AND discount_amount >= 0 AND total_amount >= 0
        ),
    CONSTRAINT ck_bookings_total_is_net CHECK (total_amount = subtotal_amount - discount_amount),
    CONSTRAINT ck_bookings_discount_requires_code CHECK (
        discount_amount = 0 OR discount_code_id IS NOT NULL
        )
);

CREATE INDEX ix_bookings_user_created_at ON bookings (user_id, created_at DESC);
CREATE INDEX ix_bookings_show ON bookings (show_id);
CREATE INDEX ix_bookings_hold_expiry ON bookings (hold_expires_at)
    WHERE status IN ('HOLD_CREATED', 'PAYMENT_PENDING');

-- Narrow working set for the reminder sweep.
CREATE INDEX ix_bookings_reminder_pending ON bookings (show_id)
    WHERE status = 'CONFIRMED' AND reminder_sent_at IS NULL;

-- ---------------------------------------------------------------------------
-- booking_seats
--
-- Carries the price snapshot required by the brief: the seat label, category,
-- resolved day type and the exact price charged. Later edits to pricing_rules
-- can never retroactively change what a customer was charged.
-- ---------------------------------------------------------------------------
CREATE TABLE booking_seats
(
    id            UUID           NOT NULL,
    booking_id    UUID           NOT NULL,
    show_seat_id  UUID           NOT NULL,
    seat_id       UUID           NOT NULL,
    row_label     VARCHAR(5)     NOT NULL,
    seat_number   INTEGER        NOT NULL,
    seat_category VARCHAR(20)    NOT NULL,
    day_type      VARCHAR(20)    NOT NULL,
    price         NUMERIC(10, 2) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_booking_seats PRIMARY KEY (id),
    CONSTRAINT fk_booking_seats_booking FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_seats_show_seat FOREIGN KEY (show_seat_id) REFERENCES show_seats (id),
    CONSTRAINT fk_booking_seats_seat FOREIGN KEY (seat_id) REFERENCES seats (id),

    CONSTRAINT uq_booking_seats_booking_show_seat UNIQUE (booking_id, show_seat_id),
    CONSTRAINT ck_booking_seats_price_non_negative CHECK (price >= 0),
    CONSTRAINT ck_booking_seats_category CHECK (seat_category IN ('REGULAR', 'PREMIUM')),
    CONSTRAINT ck_booking_seats_day_type CHECK (day_type IN ('WEEKDAY', 'WEEKEND'))
);

CREATE INDEX ix_booking_seats_booking ON booking_seats (booking_id);
CREATE INDEX ix_booking_seats_show_seat ON booking_seats (show_seat_id);

-- ---------------------------------------------------------------------------
-- discount_code_usages
--
-- One live row per redeeming booking. The row is inserted when the hold is
-- created and deleted (with used_count decremented in the same transaction)
-- whenever the booking is released - expiry, payment failure or cancellation.
-- That keeps used_count == COUNT(*) as an invariant.
-- ---------------------------------------------------------------------------
CREATE TABLE discount_code_usages
(
    id               UUID           NOT NULL,
    discount_code_id UUID           NOT NULL,
    user_id          UUID           NOT NULL,
    booking_id       UUID           NOT NULL,
    discount_amount  NUMERIC(12, 2) NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_discount_code_usages PRIMARY KEY (id),
    CONSTRAINT fk_discount_code_usages_code FOREIGN KEY (discount_code_id) REFERENCES discount_codes (id),
    CONSTRAINT fk_discount_code_usages_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_discount_code_usages_booking FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE CASCADE,

    -- A booking can redeem at most one code, exactly once.
    CONSTRAINT uq_discount_code_usages_booking UNIQUE (booking_id),
    CONSTRAINT ck_discount_code_usages_amount_non_negative CHECK (discount_amount >= 0)
);

CREATE INDEX ix_discount_code_usages_code_user ON discount_code_usages (discount_code_id, user_id);

-- ---------------------------------------------------------------------------
-- payments
--
-- idempotency_key is supplied by the client and enforced unique, so a retried
-- payment request can never charge twice.
-- ---------------------------------------------------------------------------
CREATE TABLE payments
(
    id                UUID           NOT NULL,
    booking_id        UUID           NOT NULL,
    idempotency_key   VARCHAR(100)   NOT NULL,
    amount            NUMERIC(12, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    method_token      VARCHAR(100)   NOT NULL,
    gateway_reference VARCHAR(100),
    failure_reason    VARCHAR(255),
    processed_at      TIMESTAMPTZ,
    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_payments_amount_non_negative CHECK (amount >= 0)
);

CREATE INDEX ix_payments_booking ON payments (booking_id);

-- A booking can have at most one successful payment, enforced by the database
-- rather than by application code. This is what makes "confirmed bookings
-- cannot be double-confirmed" a structural guarantee.
CREATE UNIQUE INDEX ux_payments_booking_successful ON payments (booking_id)
    WHERE status = 'SUCCESS';
