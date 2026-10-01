-- ===========================================================================
-- V900 - Demo data.
--
-- Loaded only when db/seed is on spring.flyway.locations, which the default
-- profile does and the test profile deliberately does not. Tests build their
-- own explicit fixtures instead, so nothing here can silently satisfy an
-- assertion.
--
-- The version number is far above the schema migrations so that seed data can
-- be added, removed or re-numbered without ever colliding with them.
--
-- Everything is written to be re-runnable-safe within a fresh database and to
-- stay valid over time: show times are computed relative to now() rather than
-- hard-coded, so a checkout six months from now still has bookable shows.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- UUIDv7 helper.
--
-- The application generates v7 ids (time-ordered, index-friendly); seed rows
-- should look the same rather than being obvious v4 outliers. Layout:
--   48-bit big-endian millisecond timestamp | version nibble 7 | 12 random bits
--   | variant nibble 8 | 62 random bits
-- Dropped again at the end of this migration so it does not outlive the seed.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION seed_uuid_v7() RETURNS uuid
    LANGUAGE sql VOLATILE AS
$$
SELECT (
           lpad(to_hex((extract(EPOCH FROM clock_timestamp()) * 1000)::BIGINT), 12, '0')
               || '7' || substr(replace(gen_random_uuid()::TEXT, '-', ''), 14, 3)
               || '8' || substr(replace(gen_random_uuid()::TEXT, '-', ''), 18, 15)
           )::uuid
$$;

-- ---------------------------------------------------------------------------
-- Users
--
-- BCrypt hashes below were generated with the application's own
-- BCryptPasswordEncoder (strength 10) and verified to match. Plaintext
-- passwords are documented in the README; they are development credentials and
-- nothing else.
--
--   admin        / Admin@12345    ROLE_ADMIN
--   ops.manager  / Admin@12345    ROLE_ADMIN
--   aisha        / Customer@123   ROLE_CUSTOMER
--   rahul        / Customer@123   ROLE_CUSTOMER
--   meera        / Customer@123   ROLE_CUSTOMER
-- ---------------------------------------------------------------------------
INSERT INTO users (id, username, email, password_hash, full_name, phone_number, role, enabled, created_at, updated_at)
VALUES ('01930000-0000-7000-8000-000000000001', 'admin', 'admin@mtb.example',
        '$2a$10$YmJ0UAsX8gWjILoELgG5/.Lc.5p0rJl2DhafM2YFaA6lODs6qbfmy',
        'Platform Administrator', '+919000000001', 'ADMIN', TRUE, now(), now()),
       ('01930000-0000-7000-8000-000000000002', 'ops.manager', 'ops@mtb.example',
        '$2a$10$k5We5fjV/StFoR..jEJzEukEo7Xvlm09Z5aL0p11wKFBn9lG7x4Y2',
        'Operations Manager', '+919000000002', 'ADMIN', TRUE, now(), now()),
       ('01930000-0000-7000-8000-000000000011', 'aisha', 'aisha@example.com',
        '$2a$10$N56VwMlXievyLRFShjhLGejLOn5X3GpC5fvuFE/r7vqZ8H0aQm//S',
        'Aisha Khan', '+919000000011', 'CUSTOMER', TRUE, now(), now()),
       ('01930000-0000-7000-8000-000000000012', 'rahul', 'rahul@example.com',
        '$2a$10$gPdzzPziLoVcBh/mzV.JG.IUvegrRYGJgU0JMZEsSRabq8wB6d4hi',
        'Rahul Verma', '+919000000012', 'CUSTOMER', TRUE, now(), now()),
       ('01930000-0000-7000-8000-000000000013', 'meera', 'meera@example.com',
        '$2a$10$2H9Kmgi/05Nx4lXizur4ROp7PdMiNXI8LVzO.YWSgG2JtQc6Unw/C',
        'Meera Nair', '+919000000013', 'CUSTOMER', TRUE, now(), now());

-- ---------------------------------------------------------------------------
-- Cities
--
-- Dubai is here on purpose: it sits in a different time zone from the Indian
-- cities, which makes the weekday/weekend pricing decision observably local
-- rather than UTC-based. (Currency is a single platform-wide setting - see
-- README "Trade-offs".)
-- ---------------------------------------------------------------------------
INSERT INTO cities (id, name, state, country, time_zone, active, created_at, updated_at)
VALUES ('01930000-0001-7000-8000-000000000001', 'Mumbai', 'Maharashtra', 'India', 'Asia/Kolkata', TRUE, now(), now()),
       ('01930000-0001-7000-8000-000000000002', 'Bengaluru', 'Karnataka', 'India', 'Asia/Kolkata', TRUE, now(), now()),
       ('01930000-0001-7000-8000-000000000003', 'Dubai', 'Dubai', 'United Arab Emirates', 'Asia/Dubai', TRUE, now(),
        now());

-- ---------------------------------------------------------------------------
-- Movies
-- ---------------------------------------------------------------------------
INSERT INTO movies (id, title, language, certification, duration_minutes, release_date, synopsis, active, created_at,
                    updated_at)
VALUES ('01930000-0002-7000-8000-000000000001', 'The Silent Orbit', 'English', 'UA', 142, CURRENT_DATE - 10,
        'A salvage crew answers a distress call from a station that has been dark for thirty years.', TRUE, now(),
        now()),
       ('01930000-0002-7000-8000-000000000002', 'Monsoon Letters', 'Hindi', 'U', 128, CURRENT_DATE - 25,
        'Two strangers exchange letters across a city that floods every July.', TRUE, now(), now()),
       ('01930000-0002-7000-8000-000000000003', 'Kaveri', 'Kannada', 'UA', 155, CURRENT_DATE - 4,
        'A river engineer returns to the village her dam displaced.', TRUE, now(), now()),
       ('01930000-0002-7000-8000-000000000004', 'Night Shift', 'English', 'A', 111, CURRENT_DATE - 60,
        'One hospital, one night, and a power cut that will not end.', TRUE, now(), now());

-- ---------------------------------------------------------------------------
-- Theaters
-- ---------------------------------------------------------------------------
INSERT INTO theaters (id, city_id, name, address, active, created_at, updated_at)
VALUES ('01930000-0003-7000-8000-000000000001', '01930000-0001-7000-8000-000000000001',
        'Marine Drive Cineplex', '12 Marine Drive, Churchgate, Mumbai 400020', TRUE, now(), now()),
       ('01930000-0003-7000-8000-000000000002', '01930000-0001-7000-8000-000000000001',
        'Andheri Grand', '88 Link Road, Andheri West, Mumbai 400053', TRUE, now(), now()),
       ('01930000-0003-7000-8000-000000000003', '01930000-0001-7000-8000-000000000002',
        'Indiranagar Picturehouse', '100 Feet Road, Indiranagar, Bengaluru 560038', TRUE, now(), now()),
       ('01930000-0003-7000-8000-000000000004', '01930000-0001-7000-8000-000000000003',
        'Marina Screens Dubai', 'Dubai Marina Walk, Dubai', TRUE, now(), now());

-- ---------------------------------------------------------------------------
-- Screens
-- ---------------------------------------------------------------------------
INSERT INTO screens (id, theater_id, name, screen_type, active, created_at, updated_at)
VALUES ('01930000-0004-7000-8000-000000000001', '01930000-0003-7000-8000-000000000001', 'Screen 1', 'IMAX', TRUE,
        now(), now()),
       ('01930000-0004-7000-8000-000000000002', '01930000-0003-7000-8000-000000000001', 'Screen 2', 'STANDARD', TRUE,
        now(), now()),
       ('01930000-0004-7000-8000-000000000003', '01930000-0003-7000-8000-000000000002', 'Audi A', 'RECLINER', TRUE,
        now(), now()),
       ('01930000-0004-7000-8000-000000000004', '01930000-0003-7000-8000-000000000003', 'Screen 1', 'STANDARD', TRUE,
        now(), now()),
       ('01930000-0004-7000-8000-000000000005', '01930000-0003-7000-8000-000000000004', 'Screen 1', 'FOUR_DX', TRUE,
        now(), now());

-- ---------------------------------------------------------------------------
-- Seat layouts
--
-- Generated rather than enumerated: rows A-C are PREMIUM (front of house),
-- D-H are REGULAR. One statement per screen shape keeps the file readable and
-- the seat counts obvious.
-- ---------------------------------------------------------------------------
INSERT INTO seats (id, screen_id, row_label, seat_number, category, active, created_at, updated_at)
SELECT seed_uuid_v7(),
       layout.screen_id,
       layout.row_label,
       seat_number,
       layout.category,
       TRUE,
       now(),
       now()
FROM (VALUES
          -- Marine Drive Screen 1 (IMAX): 3 premium rows of 10, 5 regular rows of 12
          ('01930000-0004-7000-8000-000000000001'::uuid, 'A', 10, 'PREMIUM'),
          ('01930000-0004-7000-8000-000000000001'::uuid, 'B', 10, 'PREMIUM'),
          ('01930000-0004-7000-8000-000000000001'::uuid, 'C', 10, 'PREMIUM'),
          ('01930000-0004-7000-8000-000000000001'::uuid, 'D', 12, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000001'::uuid, 'E', 12, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000001'::uuid, 'F', 12, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000001'::uuid, 'G', 12, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000001'::uuid, 'H', 12, 'REGULAR'),

          -- Marine Drive Screen 2: small, all regular
          ('01930000-0004-7000-8000-000000000002'::uuid, 'A', 8, 'PREMIUM'),
          ('01930000-0004-7000-8000-000000000002'::uuid, 'B', 10, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000002'::uuid, 'C', 10, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000002'::uuid, 'D', 10, 'REGULAR'),

          -- Andheri Audi A (recliner): every seat premium
          ('01930000-0004-7000-8000-000000000003'::uuid, 'A', 6, 'PREMIUM'),
          ('01930000-0004-7000-8000-000000000003'::uuid, 'B', 6, 'PREMIUM'),
          ('01930000-0004-7000-8000-000000000003'::uuid, 'C', 6, 'PREMIUM'),

          -- Indiranagar Screen 1
          ('01930000-0004-7000-8000-000000000004'::uuid, 'A', 10, 'PREMIUM'),
          ('01930000-0004-7000-8000-000000000004'::uuid, 'B', 12, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000004'::uuid, 'C', 12, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000004'::uuid, 'D', 12, 'REGULAR'),

          -- Dubai Screen 1
          ('01930000-0004-7000-8000-000000000005'::uuid, 'A', 8, 'PREMIUM'),
          ('01930000-0004-7000-8000-000000000005'::uuid, 'B', 10, 'REGULAR'),
          ('01930000-0004-7000-8000-000000000005'::uuid, 'C', 10, 'REGULAR')
     ) AS layout(screen_id, row_label, seat_count, category),
     LATERAL generate_series(1, layout.seat_count) AS seat_number;

-- ---------------------------------------------------------------------------
-- Pricing rules
--
-- Demonstrates the whole specificity cascade:
--   GLOBAL   baseline for every screen on the platform
--   CITY     Mumbai charges more than the baseline
--   SCREEN   the IMAX screen charges more again
-- PricingService picks the most specific match per seat category, so a premium
-- weekend seat on Marine Drive Screen 1 resolves to 750, the same seat on
-- Marine Drive Screen 2 to 540 (Mumbai), and in Bengaluru to 450 (global).
-- ---------------------------------------------------------------------------
INSERT INTO pricing_rules (id, city_id, theater_id, screen_id, seat_category, day_type, price, active, created_at,
                           updated_at)
VALUES
    -- Global baseline
    (seed_uuid_v7(), NULL, NULL, NULL, 'REGULAR', 'WEEKDAY', 200.00, TRUE, now(), now()),
    (seed_uuid_v7(), NULL, NULL, NULL, 'REGULAR', 'WEEKEND', 260.00, TRUE, now(), now()),
    (seed_uuid_v7(), NULL, NULL, NULL, 'PREMIUM', 'WEEKDAY', 350.00, TRUE, now(), now()),
    (seed_uuid_v7(), NULL, NULL, NULL, 'PREMIUM', 'WEEKEND', 450.00, TRUE, now(), now()),

    -- Mumbai overrides the baseline
    (seed_uuid_v7(), '01930000-0001-7000-8000-000000000001', NULL, NULL, 'REGULAR', 'WEEKDAY', 250.00, TRUE, now(),
     now()),
    (seed_uuid_v7(), '01930000-0001-7000-8000-000000000001', NULL, NULL, 'REGULAR', 'WEEKEND', 320.00, TRUE, now(),
     now()),
    (seed_uuid_v7(), '01930000-0001-7000-8000-000000000001', NULL, NULL, 'PREMIUM', 'WEEKDAY', 420.00, TRUE, now(),
     now()),
    (seed_uuid_v7(), '01930000-0001-7000-8000-000000000001', NULL, NULL, 'PREMIUM', 'WEEKEND', 540.00, TRUE, now(),
     now()),

    -- The IMAX screen overrides Mumbai
    (seed_uuid_v7(), NULL, NULL, '01930000-0004-7000-8000-000000000001', 'PREMIUM', 'WEEKDAY', 600.00, TRUE, now(),
     now()),
    (seed_uuid_v7(), NULL, NULL, '01930000-0004-7000-8000-000000000001', 'PREMIUM', 'WEEKEND', 750.00, TRUE, now(),
     now());

-- ---------------------------------------------------------------------------
-- Refund policies
--
-- "Standard" is the platform default. "Andheri Flexible" shows a venue
-- overriding it with its own, more generous terms.
-- ---------------------------------------------------------------------------
INSERT INTO refund_policies (id, name, description, theater_id, is_default, active, created_at, updated_at)
VALUES ('01930000-0005-7000-8000-000000000001', 'Standard',
        'Platform default: full refund beyond 24h, half between 12h and 24h, none inside 12h',
        NULL, TRUE, TRUE, now(), now()),
       ('01930000-0005-7000-8000-000000000002', 'Andheri Flexible',
        'Full refund until 2 hours before the show',
        '01930000-0003-7000-8000-000000000002', FALSE, TRUE, now(), now());

INSERT INTO refund_policy_rules (id, refund_policy_id, min_hours_before_show, max_hours_before_show, refund_percentage,
                                 created_at, updated_at)
VALUES
    -- Standard: [0,12) = 0%, [12,24) = 50%, [24,inf) = 100%
    (seed_uuid_v7(), '01930000-0005-7000-8000-000000000001', 0, 12, 0.00, now(), now()),
    (seed_uuid_v7(), '01930000-0005-7000-8000-000000000001', 12, 24, 50.00, now(), now()),
    (seed_uuid_v7(), '01930000-0005-7000-8000-000000000001', 24, NULL, 100.00, now(), now()),

    -- Andheri Flexible: [0,2) = 0%, [2,inf) = 100%
    (seed_uuid_v7(), '01930000-0005-7000-8000-000000000002', 0, 2, 0.00, now(), now()),
    (seed_uuid_v7(), '01930000-0005-7000-8000-000000000002', 2, NULL, 100.00, now(), now());

-- ---------------------------------------------------------------------------
-- Discount codes
--
-- One of each kind, plus two that are supposed to fail so the rejection paths
-- can be exercised by hand without editing data first.
-- ---------------------------------------------------------------------------
INSERT INTO discount_codes (id, code, description, discount_type, discount_value, max_discount_amount,
                            min_booking_amount, valid_from, valid_until, usage_limit, per_user_limit, used_count,
                            active, created_at, updated_at)
VALUES (seed_uuid_v7(), 'WELCOME50', '50% off your first booking, capped at 150',
        'PERCENTAGE', 50.00, 150.00, 300.00, now() - interval '30 days', now() + interval '365 days',
        1000, 1, 0, TRUE, now(), now()),
       (seed_uuid_v7(), 'FLAT100', 'Flat 100 off bookings over 500',
        'FIXED_AMOUNT', 100.00, NULL, 500.00, now() - interval '30 days', now() + interval '365 days',
        NULL, NULL, 0, TRUE, now(), now()),
       (seed_uuid_v7(), 'WEEKEND20', '20% off weekend shows, capped at 200',
        'PERCENTAGE', 20.00, 200.00, 0.00, now() - interval '7 days', now() + interval '180 days',
        500, 3, 0, TRUE, now(), now()),
       (seed_uuid_v7(), 'EXPIRED10', 'Deliberately expired - exercises DISCOUNT_EXPIRED',
        'PERCENTAGE', 10.00, NULL, 0.00, now() - interval '60 days', now() - interval '30 days',
        NULL, NULL, 0, TRUE, now(), now()),
       (seed_uuid_v7(), 'PAUSED25', 'Deliberately inactive - exercises DISCOUNT_INACTIVE',
        'PERCENTAGE', 25.00, NULL, 0.00, now() - interval '7 days', now() + interval '180 days',
        NULL, NULL, 0, FALSE, now(), now());

-- ---------------------------------------------------------------------------
-- Shows
--
-- Times are computed from now(), never hard-coded, so the seed stays valid
-- however long after it was written the project is checked out.
--
--   date_trunc('week', ...) is the Monday of the current local week. Adding
--   8/9 days lands on next Tuesday/Wednesday (always a weekday, always in the
--   future); adding 12/13 days lands on next Saturday/Sunday.
--
-- The "refund demo" shows sit at fixed offsets from now so every band can be
-- exercised immediately, without waiting or editing data:
--   +30h on a Standard-policy screen -> 100% band
--   +18h on a Standard-policy screen ->  50% band
--   +6h  on a Standard-policy screen ->   0% band
--   +6h  on Andheri (Flexible policy) -> 100%, despite being only 6h away.
-- The last one is the point: same elapsed time, different answer, because the
-- theater overrides the platform default.
-- ---------------------------------------------------------------------------
INSERT INTO shows (id, screen_id, movie_id, starts_at, ends_at, status, created_at, updated_at)
SELECT show_id,
       screen_id,
       movie_id,
       starts_at,
       starts_at + (duration_minutes || ' minutes')::INTERVAL + interval '20 minutes',
       'SCHEDULED',
       now(),
       now()
FROM (
         SELECT '01930000-0006-7000-8000-000000000001'::uuid                            AS show_id,
                '01930000-0004-7000-8000-000000000001'::uuid                            AS screen_id,
                '01930000-0002-7000-8000-000000000001'::uuid                            AS movie_id,
                142                                                                     AS duration_minutes,
                (ist_week_start + interval '8 days 18 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata' AS starts_at
         FROM (SELECT date_trunc('week', (now() AT TIME ZONE 'Asia/Kolkata')) AS ist_week_start) w

         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000002'::uuid,
                '01930000-0004-7000-8000-000000000001'::uuid,
                '01930000-0002-7000-8000-000000000001'::uuid,
                142,
                (ist_week_start + interval '12 days 19 hours') AT TIME ZONE 'Asia/Kolkata'
         FROM (SELECT date_trunc('week', (now() AT TIME ZONE 'Asia/Kolkata')) AS ist_week_start) w

         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000003'::uuid,
                '01930000-0004-7000-8000-000000000002'::uuid,
                '01930000-0002-7000-8000-000000000002'::uuid,
                128,
                (ist_week_start + interval '9 days 21 hours') AT TIME ZONE 'Asia/Kolkata'
         FROM (SELECT date_trunc('week', (now() AT TIME ZONE 'Asia/Kolkata')) AS ist_week_start) w

         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000004'::uuid,
                '01930000-0004-7000-8000-000000000002'::uuid,
                '01930000-0002-7000-8000-000000000002'::uuid,
                128,
                (ist_week_start + interval '13 days 12 hours') AT TIME ZONE 'Asia/Kolkata'
         FROM (SELECT date_trunc('week', (now() AT TIME ZONE 'Asia/Kolkata')) AS ist_week_start) w

         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000005'::uuid,
                '01930000-0004-7000-8000-000000000004'::uuid,
                '01930000-0002-7000-8000-000000000003'::uuid,
                155,
                (ist_week_start + interval '12 days 20 hours') AT TIME ZONE 'Asia/Kolkata'
         FROM (SELECT date_trunc('week', (now() AT TIME ZONE 'Asia/Kolkata')) AS ist_week_start) w

             -- Dubai: same wall-clock Saturday evening, a different absolute instant.
         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000006'::uuid,
                '01930000-0004-7000-8000-000000000005'::uuid,
                '01930000-0002-7000-8000-000000000004'::uuid,
                111,
                (gst_week_start + interval '12 days 20 hours') AT TIME ZONE 'Asia/Dubai'
         FROM (SELECT date_trunc('week', (now() AT TIME ZONE 'Asia/Dubai')) AS gst_week_start) w

             -- Refund demo: Standard policy, 100% band (Indiranagar, Bengaluru)
         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000007'::uuid,
                '01930000-0004-7000-8000-000000000004'::uuid,
                '01930000-0002-7000-8000-000000000004'::uuid,
                111,
                now() + interval '30 hours'

             -- Refund demo: Standard policy, 50% band (Marine Drive Screen 2)
         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000008'::uuid,
                '01930000-0004-7000-8000-000000000002'::uuid,
                '01930000-0002-7000-8000-000000000004'::uuid,
                111,
                now() + interval '18 hours'

             -- Refund demo: Standard policy, 0% band (Marine Drive Screen 2)
         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000009'::uuid,
                '01930000-0004-7000-8000-000000000002'::uuid,
                '01930000-0002-7000-8000-000000000003'::uuid,
                155,
                now() + interval '6 hours'

             -- Refund demo: same 6 hours out, but Andheri's Flexible policy
             -- refunds in full. Contrast with the show above.
         UNION ALL
         SELECT '01930000-0006-7000-8000-000000000010'::uuid,
                '01930000-0004-7000-8000-000000000003'::uuid,
                '01930000-0002-7000-8000-000000000003'::uuid,
                155,
                now() + interval '6 hours'
     ) AS planned;

-- ---------------------------------------------------------------------------
-- Show seat inventory
--
-- One AVAILABLE row per active seat of each show's screen. In the running
-- application ShowService does this inside the show-creation transaction; here
-- a single set-based statement covers every seeded show at once.
-- ---------------------------------------------------------------------------
INSERT INTO show_seats (id, show_id, seat_id, status, version, created_at, updated_at)
SELECT seed_uuid_v7(), sh.id, se.id, 'AVAILABLE', 0, now(), now()
FROM shows sh
         JOIN seats se ON se.screen_id = sh.screen_id AND se.active
ORDER BY sh.id, se.id;

DROP FUNCTION seed_uuid_v7();
