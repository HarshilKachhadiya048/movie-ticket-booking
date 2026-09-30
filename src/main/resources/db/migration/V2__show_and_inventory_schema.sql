-- ===========================================================================
-- V2 - Shows and the per-show seat inventory.
--
-- show_seats is the single source of truth for seat allocation and the only
-- table the booking path locks. Every correctness guarantee about "two users
-- can never get the same seat" is enforced here:
--
--   * uq_show_seats_show_seat   - one inventory row per (show, seat), so a
--                                 duplicate allocation cannot even be
--                                 represented.
--   * SELECT ... FOR UPDATE     - issued by ShowSeatRepository via JPA
--                                 @Lock(PESSIMISTIC_WRITE); serialises
--                                 concurrent holds on the same rows.
--   * ck_show_seats_hold_fields - a row is either cleanly HELD (token, holder
--                                 and expiry all present) or has no hold state
--                                 at all. Makes a half-released seat
--                                 unrepresentable.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- shows
-- ---------------------------------------------------------------------------
CREATE TABLE shows
(
    id         UUID        NOT NULL,
    screen_id  UUID        NOT NULL,
    movie_id   UUID        NOT NULL,
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,
    status     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_shows PRIMARY KEY (id),
    CONSTRAINT fk_shows_screen FOREIGN KEY (screen_id) REFERENCES screens (id),
    CONSTRAINT fk_shows_movie FOREIGN KEY (movie_id) REFERENCES movies (id),
    CONSTRAINT uq_shows_screen_starts_at UNIQUE (screen_id, starts_at),
    CONSTRAINT ck_shows_time_range CHECK (ends_at > starts_at),
    CONSTRAINT ck_shows_status CHECK (status IN ('SCHEDULED', 'CANCELLED', 'COMPLETED'))
);

CREATE INDEX ix_shows_screen_starts_at ON shows (screen_id, starts_at);
CREATE INDEX ix_shows_movie ON shows (movie_id);
CREATE INDEX ix_shows_starts_at ON shows (starts_at);

-- ---------------------------------------------------------------------------
-- show_seats
-- ---------------------------------------------------------------------------
CREATE TABLE show_seats
(
    id               UUID        NOT NULL,
    show_id          UUID        NOT NULL,
    seat_id          UUID        NOT NULL,
    status           VARCHAR(20) NOT NULL,
    hold_token       UUID,
    held_by_user_id  UUID,
    hold_expires_at  TIMESTAMPTZ,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_show_seats PRIMARY KEY (id),
    CONSTRAINT fk_show_seats_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT fk_show_seats_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    CONSTRAINT fk_show_seats_held_by FOREIGN KEY (held_by_user_id) REFERENCES users (id),

    -- The structural guarantee against double allocation.
    CONSTRAINT uq_show_seats_show_seat UNIQUE (show_id, seat_id),

    CONSTRAINT ck_show_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),

    -- Hold state is all-or-nothing. Releasing a seat MUST clear every hold
    -- column; confirming a seat MUST do the same.
    CONSTRAINT ck_show_seats_hold_fields CHECK (
        (status = 'HELD'
            AND hold_token IS NOT NULL
            AND held_by_user_id IS NOT NULL
            AND hold_expires_at IS NOT NULL)
            OR
        (status <> 'HELD'
            AND hold_token IS NULL
            AND held_by_user_id IS NULL
            AND hold_expires_at IS NULL)
        )
);

-- Seat-map rendering for a show.
CREATE INDEX ix_show_seats_show_status ON show_seats (show_id, status);

-- Drives the hold sweeper without scanning the whole table: only rows that
-- can possibly be expired are indexed.
CREATE INDEX ix_show_seats_hold_expiry ON show_seats (hold_expires_at)
    WHERE status = 'HELD';

CREATE INDEX ix_show_seats_held_by ON show_seats (held_by_user_id)
    WHERE held_by_user_id IS NOT NULL;
