package com.harshil.movieticketbooking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.dto.PayBookingRequest;
import com.harshil.movieticketbooking.booking.service.BookingPaymentService;
import com.harshil.movieticketbooking.booking.service.CreateHoldCommand;
import com.harshil.movieticketbooking.booking.service.SeatHoldService;
import com.harshil.movieticketbooking.payment.gateway.PaymentMethodToken;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.TestScenario;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schema itself: that the migrations produce what the entities expect, and
 * that the constraints the design leans on actually reject bad data.
 * <p>
 * Every assertion here bypasses JPA and writes raw SQL, which is the point.
 * Claiming "the database guarantees X" is only worth something if X holds
 * against a statement that did not go through the application's validation.
 * <p>
 * The fact that this context starts at all is itself an assertion:
 * {@code spring.jpa.hibernate.ddl-auto=validate} means Hibernate compared
 * every entity mapping against the migrated schema and found no drift.
 */
class SchemaMigrationIT extends AbstractIntegrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "users", "cities", "movies", "theaters", "screens", "seats",
            "shows", "show_seats",
            "pricing_rules", "discount_codes", "discount_code_usages",
            "bookings", "booking_seats", "payments",
            "refund_policies", "refund_policy_rules", "refunds",
            "notifications");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private BookingPaymentService bookingPaymentService;

    @Nested
    @DisplayName("migrations")
    class Migrations {

        @Test
        void everyMigrationAppliedSuccessfully() {
            List<String> failed = jdbcTemplate.queryForList(
                    "SELECT version FROM flyway_schema_history WHERE success = FALSE", String.class);
            Integer applied = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);

            assertThat(failed).isEmpty();
            assertThat(applied).isGreaterThanOrEqualTo(6);
        }

        @Test
        void everyExpectedTableExists() {
            List<String> tables = jdbcTemplate.queryForList(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);

            assertThat(tables).containsAll(EXPECTED_TABLES);
        }

        /**
         * The test profile excludes {@code db/seed}, so a test can never be
         * satisfied by demo data it did not create.
         */
        @Test
        void demoSeedDataIsNotLoadedInTests() {
            Integer cities = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cities", Integer.class);
            assertThat(cities).isZero();
        }

        @Test
        void theHotPathIndexesExist() {
            List<String> indexes = jdbcTemplate.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);

            assertThat(indexes).contains(
                    "uq_show_seats_show_seat",
                    "ix_show_seats_show_status",
                    "ix_show_seats_hold_expiry",
                    "ux_payments_booking_successful",
                    "uq_refunds_booking",
                    "uq_notifications_dedupe_key",
                    "ix_bookings_reminder_pending");
        }
    }

    @Nested
    @DisplayName("constraints that the design depends on")
    class Constraints {

        /**
         * The structural half of the no-double-allocation guarantee: a second
         * inventory row for the same seat of the same show cannot exist, so
         * duplicate allocation is not merely prevented, it is unrepresentable.
         */
        @Test
        void aShowCannotHaveTwoInventoryRowsForOneSeat() {
            TestScenario scenario = testData.createBookableShow();
            UUID seatId = scenario.firstSeat();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO show_seats (id, show_id, seat_id, status, version, created_at, updated_at)
                    VALUES (gen_random_uuid(), ?, ?, 'AVAILABLE', 0, now(), now())
                    """, scenario.showId(), seatId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * {@code ck_show_seats_hold_fields} makes a half-released seat
         * impossible: HELD requires every hold column, and anything else
         * requires none of them.
         */
        @Test
        void aHeldSeatMustCarryItsWholeHoldState() {
            TestScenario scenario = testData.createBookableShow();
            UUID seatId = scenario.firstSeat();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    UPDATE show_seats SET status = 'HELD' WHERE show_id = ? AND seat_id = ?
                    """, scenario.showId(), seatId))
                    .as("HELD without a token, holder or expiry must be rejected")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void aFreeSeatMustNotCarryLeftoverHoldState() {
            TestScenario scenario = testData.createBookableShow();
            UUID seatId = scenario.firstSeat();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    UPDATE show_seats
                    SET status = 'AVAILABLE', hold_token = gen_random_uuid()
                    WHERE show_id = ? AND seat_id = ?
                    """, scenario.showId(), seatId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /** Why "a confirmed booking cannot be double-confirmed" is structural. */
        @Test
        void aBookingCannotHaveTwoSuccessfulPayments() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse confirmed = holdAndPay(scenario);

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO payments (id, booking_id, idempotency_key, amount, currency, status,
                                          method_token, version, created_at, updated_at)
                    VALUES (gen_random_uuid(), ?, ?, 100.00, 'INR', 'SUCCESS', 'pm_success', 0, now(), now())
                    """, confirmed.bookingId(), "manual-" + UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /** A failed attempt alongside a successful one is fine - only SUCCESS is unique. */
        @Test
        void aBookingMayHaveManyFailedPayments() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario);

            assertThatCode(() -> {
                insertFailedPayment(held.bookingId());
                insertFailedPayment(held.bookingId());
            }).doesNotThrowAnyException();
        }

        /** The idempotency guarantee for cancellation. */
        @Test
        void aBookingCannotHaveTwoRefunds() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse confirmed = holdAndPay(scenario);
            UUID paymentId = jdbcTemplate.queryForObject(
                    "SELECT id FROM payments WHERE booking_id = ? AND status = 'SUCCESS'",
                    UUID.class, confirmed.bookingId());

            insertRefund(confirmed.bookingId(), paymentId);

            assertThatThrownBy(() -> insertRefund(confirmed.bookingId(), paymentId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void bookingTotalsMustBeInternallyConsistent() {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse held = hold(scenario);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE bookings SET total_amount = total_amount + 1 WHERE id = ?", held.bookingId()))
                    .as("total must always equal subtotal minus discount")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void aPricingRuleMayNotBeScopedAtTwoLevelsAtOnce() {
            TestScenario scenario = testData.createBookableShow();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO pricing_rules (id, city_id, theater_id, seat_category, day_type, price,
                                               active, created_at, updated_at)
                    VALUES (gen_random_uuid(), ?, ?, 'REGULAR', 'WEEKDAY', 100.00, TRUE, now(), now())
                    """, scenario.cityId(), scenario.theaterId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        /**
         * Uses {@code UNIQUE NULLS NOT DISTINCT} (PostgreSQL 15+), so the
         * global rules - where all three scope columns are NULL - are
         * deduplicated too. Standard SQL would treat those NULLs as distinct
         * and let duplicates through.
         */
        @Test
        void twoGlobalPricingRulesForTheSameKeyAreRejected() {
            testData.createBookableShow();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO pricing_rules (id, seat_category, day_type, price, active, created_at, updated_at)
                    VALUES (gen_random_uuid(), 'REGULAR', 'WEEKDAY', 999.00, TRUE, now(), now())
                    """))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void onlyOneDefaultRefundPolicyMayExist() {
            testData.createBookableShow();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO refund_policies (id, name, is_default, active, created_at, updated_at)
                    VALUES (gen_random_uuid(), 'Second Default', TRUE, TRUE, now(), now())
                    """))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void aPercentageDiscountCannotExceedOneHundred() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO discount_codes (id, code, discount_type, discount_value, min_booking_amount,
                                                valid_from, valid_until, used_count, active, created_at, updated_at)
                    VALUES (gen_random_uuid(), 'TOOMUCH', 'PERCENTAGE', 150.00, 0,
                            now(), now() + interval '1 day', 0, TRUE, now(), now())
                    """))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        void aShowMustEndAfterItStarts() {
            TestScenario scenario = testData.createBookableShow();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE shows SET ends_at = starts_at - interval '1 hour' WHERE id = ?", scenario.showId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("UTC round-tripping")
    class Timestamps {

        /**
         * Instants must survive the round trip unchanged regardless of the
         * JVM's default zone - the reason every timestamp column is
         * {@code TIMESTAMPTZ} rather than {@code TIMESTAMP}.
         */
        @Test
        void instantsRoundTripExactly() {
            TestScenario scenario = testData.createBookableShow();

            java.time.Instant storedStart = jdbcTemplate.queryForObject(
                            "SELECT starts_at FROM shows WHERE id = ?",
                            java.time.OffsetDateTime.class, scenario.showId())
                    .toInstant();

            assertThat(storedStart).isEqualTo(scenario.showStartsAt());
        }
    }

    // -----------------------------------------------------------------------

    private BookingResponse hold(TestScenario scenario) {
        return seatHoldService.createHold(new CreateHoldCommand(
                scenario.showId(), scenario.premiumSeatIds(), null, scenario.customerId()));
    }

    private BookingResponse holdAndPay(TestScenario scenario) {
        BookingResponse held = hold(scenario);
        return bookingPaymentService.pay(held.bookingId(), scenario.customerId(),
                new PayBookingRequest(PaymentMethodToken.SUCCESS.token(), "schema-" + UUID.randomUUID()));
    }

    private void insertFailedPayment(UUID bookingId) {
        jdbcTemplate.update("""
                INSERT INTO payments (id, booking_id, idempotency_key, amount, currency, status,
                                      method_token, version, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 100.00, 'INR', 'FAILED', 'pm_failure', 0, now(), now())
                """, bookingId, "failed-" + UUID.randomUUID());
    }

    private void insertRefund(UUID bookingId, UUID paymentId) {
        jdbcTemplate.update("""
                INSERT INTO refunds (id, booking_id, payment_id, original_amount, refund_percentage,
                                     refund_amount, currency, hours_before_show, status, version,
                                     created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 100.00, 100.00, 100.00, 'INR', 48.00, 'PENDING', 0, now(), now())
                """, bookingId, paymentId);
    }
}
