-- ===========================================================================
-- V5 - Configurable refund policies and issued refunds.
--
-- The brief's example ladder (>24h = 100%, 12-24h = 50%, <12h = 0%) is seed
-- data, not code. A policy is a named set of non-overlapping hour bands; the
-- refund percentage for a cancellation is whichever band contains the hours
-- remaining until the show starts.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- refund_policies
--
-- Resolution order used by RefundService:
--   1. the policy attached to the show's theater, if any
--   2. otherwise the single policy flagged is_default
-- ---------------------------------------------------------------------------
CREATE TABLE refund_policies
(
    id          UUID         NOT NULL,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(255),
    theater_id  UUID,
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_refund_policies PRIMARY KEY (id),
    CONSTRAINT uq_refund_policies_name UNIQUE (name),
    CONSTRAINT fk_refund_policies_theater FOREIGN KEY (theater_id) REFERENCES theaters (id),
    CONSTRAINT ck_refund_policies_default_is_global CHECK (NOT is_default OR theater_id IS NULL)
);

-- At most one theater-specific policy per theater.
CREATE UNIQUE INDEX ux_refund_policies_theater ON refund_policies (theater_id)
    WHERE theater_id IS NOT NULL;

-- At most one global default policy, enforced by the database.
CREATE UNIQUE INDEX ux_refund_policies_single_default ON refund_policies ((TRUE))
    WHERE is_default;

-- ---------------------------------------------------------------------------
-- refund_policy_rules
--
-- Half-open band: min_hours_before_show <= hours < max_hours_before_show.
-- A NULL max means "and everything above".
-- ---------------------------------------------------------------------------
CREATE TABLE refund_policy_rules
(
    id                    UUID          NOT NULL,
    refund_policy_id      UUID          NOT NULL,
    min_hours_before_show INTEGER       NOT NULL,
    max_hours_before_show INTEGER,
    refund_percentage     NUMERIC(5, 2) NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_refund_policy_rules PRIMARY KEY (id),
    CONSTRAINT fk_refund_policy_rules_policy FOREIGN KEY (refund_policy_id)
        REFERENCES refund_policies (id) ON DELETE CASCADE,

    CONSTRAINT uq_refund_policy_rules_band UNIQUE (refund_policy_id, min_hours_before_show),
    CONSTRAINT ck_refund_policy_rules_min_non_negative CHECK (min_hours_before_show >= 0),
    CONSTRAINT ck_refund_policy_rules_band_ordered CHECK (
        max_hours_before_show IS NULL OR max_hours_before_show > min_hours_before_show
        ),
    CONSTRAINT ck_refund_policy_rules_percentage_range CHECK (
        refund_percentage >= 0 AND refund_percentage <= 100
        )
);

CREATE INDEX ix_refund_policy_rules_policy ON refund_policy_rules (refund_policy_id);

-- ---------------------------------------------------------------------------
-- refunds
--
-- uq_refunds_booking is the idempotency guarantee for cancellation: a second
-- concurrent cancel of the same booking cannot create a second refund.
-- The resolved policy, matched band and hours remaining are all persisted so
-- that the amount can be justified after the fact even if the policy changes.
-- ---------------------------------------------------------------------------
CREATE TABLE refunds
(
    id                    UUID           NOT NULL,
    booking_id            UUID           NOT NULL,
    payment_id            UUID           NOT NULL,
    refund_policy_id      UUID,
    refund_policy_rule_id UUID,
    original_amount       NUMERIC(12, 2) NOT NULL,
    refund_percentage     NUMERIC(5, 2)  NOT NULL,
    refund_amount         NUMERIC(12, 2) NOT NULL,
    currency              VARCHAR(3)     NOT NULL,
    hours_before_show     NUMERIC(10, 2) NOT NULL,
    status                VARCHAR(20)    NOT NULL,
    gateway_reference     VARCHAR(100),
    failure_reason        VARCHAR(255),
    processed_at          TIMESTAMPTZ,
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ    NOT NULL,
    updated_at            TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_refunds PRIMARY KEY (id),
    CONSTRAINT fk_refunds_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_refunds_policy FOREIGN KEY (refund_policy_id) REFERENCES refund_policies (id),
    CONSTRAINT fk_refunds_policy_rule FOREIGN KEY (refund_policy_rule_id) REFERENCES refund_policy_rules (id),

    CONSTRAINT uq_refunds_booking UNIQUE (booking_id),
    CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_refunds_amounts_non_negative CHECK (original_amount >= 0 AND refund_amount >= 0),
    CONSTRAINT ck_refunds_amount_within_original CHECK (refund_amount <= original_amount),
    CONSTRAINT ck_refunds_percentage_range CHECK (refund_percentage >= 0 AND refund_percentage <= 100)
);

CREATE INDEX ix_refunds_status ON refunds (status);
