-- ===========================================================================
-- V3 - Configurable pricing and discount codes.
--
-- Nothing about price is hard-coded in Java. A price is resolved from
-- pricing_rules by (scope, seat category, day type); a discount is resolved
-- from discount_codes and validated against its own configured limits.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- pricing_rules
--
-- A rule is scoped at exactly one level - screen, theater, city, or global
-- (all three scope columns NULL). PricingService resolves the most specific
-- active rule that matches, so a global default can be overridden per city,
-- per theater, or per screen without touching code.
--
-- Deliberately not time-versioned: historical bookings are protected by the
-- price snapshot persisted on booking_seats, so changing a rule only affects
-- future bookings.
-- ---------------------------------------------------------------------------
CREATE TABLE pricing_rules
(
    id            UUID           NOT NULL,
    city_id       UUID,
    theater_id    UUID,
    screen_id     UUID,
    seat_category VARCHAR(20)    NOT NULL,
    day_type      VARCHAR(20)    NOT NULL,
    price         NUMERIC(10, 2) NOT NULL,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ    NOT NULL,
    updated_at    TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_pricing_rules PRIMARY KEY (id),
    CONSTRAINT fk_pricing_rules_city FOREIGN KEY (city_id) REFERENCES cities (id),
    CONSTRAINT fk_pricing_rules_theater FOREIGN KEY (theater_id) REFERENCES theaters (id),
    CONSTRAINT fk_pricing_rules_screen FOREIGN KEY (screen_id) REFERENCES screens (id),

    CONSTRAINT ck_pricing_rules_seat_category CHECK (seat_category IN ('REGULAR', 'PREMIUM')),
    CONSTRAINT ck_pricing_rules_day_type CHECK (day_type IN ('WEEKDAY', 'WEEKEND')),
    CONSTRAINT ck_pricing_rules_price_non_negative CHECK (price >= 0),

    -- At most one scope column may be set; none set means the global default.
    CONSTRAINT ck_pricing_rules_single_scope CHECK (
        (CASE WHEN city_id IS NOT NULL THEN 1 ELSE 0 END)
            + (CASE WHEN theater_id IS NOT NULL THEN 1 ELSE 0 END)
            + (CASE WHEN screen_id IS NOT NULL THEN 1 ELSE 0 END) <= 1
        )
);

-- NULLS NOT DISTINCT (PostgreSQL 15+) makes this also deduplicate the global
-- rules, where all three scope columns are NULL.
ALTER TABLE pricing_rules
    ADD CONSTRAINT uq_pricing_rules_scope
        UNIQUE NULLS NOT DISTINCT (city_id, theater_id, screen_id, seat_category, day_type);

CREATE INDEX ix_pricing_rules_lookup ON pricing_rules (seat_category, day_type)
    WHERE active;

-- ---------------------------------------------------------------------------
-- discount_codes
--
-- used_count is maintained as an exact mirror of the number of live rows in
-- discount_code_usages. Redemption takes a row lock on the code first, so
-- concurrent redemptions of the same code are serialised and the overall and
-- per-user limits cannot be raced past.
-- ---------------------------------------------------------------------------
CREATE TABLE discount_codes
(
    id                  UUID           NOT NULL,
    code                VARCHAR(40)    NOT NULL,
    description         VARCHAR(255),
    discount_type       VARCHAR(20)    NOT NULL,
    discount_value      NUMERIC(10, 2) NOT NULL,
    max_discount_amount NUMERIC(10, 2),
    min_booking_amount  NUMERIC(10, 2) NOT NULL DEFAULT 0,
    valid_from          TIMESTAMPTZ    NOT NULL,
    valid_until         TIMESTAMPTZ    NOT NULL,
    usage_limit         INTEGER,
    per_user_limit      INTEGER,
    used_count          INTEGER        NOT NULL DEFAULT 0,
    active              BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,

    CONSTRAINT pk_discount_codes PRIMARY KEY (id),
    CONSTRAINT uq_discount_codes_code UNIQUE (code),

    CONSTRAINT ck_discount_codes_type CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    CONSTRAINT ck_discount_codes_value_positive CHECK (discount_value > 0),
    CONSTRAINT ck_discount_codes_percentage_bound CHECK (
        discount_type <> 'PERCENTAGE' OR discount_value <= 100
        ),
    CONSTRAINT ck_discount_codes_validity_range CHECK (valid_until > valid_from),
    CONSTRAINT ck_discount_codes_min_booking_non_negative CHECK (min_booking_amount >= 0),
    CONSTRAINT ck_discount_codes_max_discount_positive CHECK (
        max_discount_amount IS NULL OR max_discount_amount > 0
        ),
    CONSTRAINT ck_discount_codes_usage_limit_positive CHECK (usage_limit IS NULL OR usage_limit > 0),
    CONSTRAINT ck_discount_codes_per_user_limit_positive CHECK (per_user_limit IS NULL OR per_user_limit > 0),
    CONSTRAINT ck_discount_codes_used_count_non_negative CHECK (used_count >= 0)
);

CREATE INDEX ix_discount_codes_active_window ON discount_codes (valid_from, valid_until)
    WHERE active;
