package com.harshil.movieticketbooking.booking;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harshil.movieticketbooking.booking.dto.BookingResponse;
import com.harshil.movieticketbooking.booking.service.CreateHoldCommand;
import com.harshil.movieticketbooking.booking.service.SeatHoldService;
import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.payment.gateway.PaymentMethodToken;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.TestDataFactory;
import com.harshil.movieticketbooking.support.TestScenario;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * The HTTP layer: authentication, authorization, validation and status codes.
 * <p>
 * Deliberately complementary to {@link BookingLifecycleIT}, which covers the
 * same flows at the service level. This one only asks questions that are
 * genuinely about HTTP - is the right status returned, is the error body the
 * documented shape, does the filter chain let the right callers through.
 */
class BookingApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SeatHoldService seatHoldService;

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        void anonymousRequestsGet401WithTheStandardErrorBody() throws Exception {
            mockMvc.perform(get(ApiEndpoints.Cities.ROOT))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.path").value(ApiEndpoints.Cities.ROOT))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        void badCredentialsGet401() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(get(ApiEndpoints.Cities.ROOT)
                            .with(httpBasic(scenario.customerUsername(), "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.name()));
        }

        @Test
        void validCredentialsReachTheEndpoint() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(get(ApiEndpoints.Cities.ROOT).with(customer(scenario)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].cityId").exists())
                    .andExpect(jsonPath("$[0].timeZone").value("Asia/Kolkata"));
        }

        /** The only endpoint that works without credentials. */
        @Test
        void registrationIsPublicAndAlwaysCreatesACustomer() throws Exception {
            String username = "newbie-" + UUID.randomUUID();

            mockMvc.perform(post(ApiEndpoints.Auth.REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "username", username,
                                    "email", username + "@example.test",
                                    "password", "Password@123",
                                    "fullName", "New Customer"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("CUSTOMER"))
                    .andExpect(jsonPath("$.userId").exists());
        }

        @Test
        void registrationNeverEchoesThePassword() throws Exception {
            String username = "quiet-" + UUID.randomUUID();

            String body = mockMvc.perform(post(ApiEndpoints.Auth.REGISTER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "username", username,
                                    "email", username + "@example.test",
                                    "password", "Sup3rSecret!",
                                    "fullName", "Quiet Customer"))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(body)
                    .doesNotContain("Sup3rSecret!")
                    .doesNotContain("passwordHash");
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        void customersAreRefusedFromAdminEndpoints() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(get(ApiEndpoints.Admin.Cities.ROOT).with(customer(scenario)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ErrorCode.ACCESS_DENIED.name()));
        }

        @Test
        void adminsAreAllowedIntoAdminEndpoints() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(get(ApiEndpoints.Admin.Cities.ROOT).with(admin(scenario)))
                    .andExpect(status().isOk());
        }

        /**
         * Administering the platform and buying a ticket are different jobs.
         * An admin account is not a customer account, so booking endpoints
         * refuse it.
         */
        @Test
        void adminsAreRefusedFromCustomerBookingEndpoints() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(get(ApiEndpoints.Bookings.ROOT).with(admin(scenario)))
                    .andExpect(status().isForbidden());
        }

        /**
         * Role alone is not enough: this caller is a customer, but not
         * <em>this</em> booking's customer.
         */
        @Test
        void customersCannotReadAnotherCustomersBooking() throws Exception {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse booking = hold(scenario, scenario.premiumSeatIds());

            mockMvc.perform(get(ApiEndpoints.Bookings.BY_ID, booking.bookingId())
                            .with(httpBasic(scenario.otherCustomerUsername(), TestDataFactory.PASSWORD)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED_BOOKING_ACCESS.name()));
        }

        @Test
        void customersCanReadTheirOwnBooking() throws Exception {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse booking = hold(scenario, scenario.premiumSeatIds());

            mockMvc.perform(get(ApiEndpoints.Bookings.BY_ID, booking.bookingId()).with(customer(scenario)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bookingReference").value(booking.bookingReference()))
                    .andExpect(jsonPath("$.seats.length()").value(2));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void missingFieldsGet400WithPerFieldDetail() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(post(ApiEndpoints.Holds.ROOT)
                            .with(customer(scenario))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("seatIds", List.of()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(jsonPath("$.fieldErrors[*].field",
                            org.hamcrest.Matchers.hasItems("showId", "seatIds")));
        }

        @Test
        void malformedJsonGet400() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(post(ApiEndpoints.Holds.ROOT)
                            .with(customer(scenario))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST.name()));
        }

        @Test
        void aMalformedPathIdGet400RatherThan500() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(get(ApiEndpoints.Bookings.ROOT + "/not-a-uuid").with(customer(scenario)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()));
        }

        @Test
        void anUnknownShowGet404() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(post(ApiEndpoints.Holds.ROOT)
                            .with(customer(scenario))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "showId", UUID.randomUUID().toString(),
                                    "seatIds", List.of(scenario.firstSeat().toString())))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SHOW_NOT_FOUND.name()));
        }
    }

    @Nested
    @DisplayName("status codes on the booking flow")
    class StatusCodes {

        @Test
        void aSuccessfulHoldReturns201WithThePayableTotal() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            mockMvc.perform(post(ApiEndpoints.Holds.ROOT)
                            .with(customer(scenario))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(holdRequest(scenario, scenario.premiumSeatIds())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("HOLD_CREATED"))
                    .andExpect(jsonPath("$.totalAmount").value(700.00))
                    .andExpect(jsonPath("$.holdExpiresAt").exists());
        }

        /** Seat contention is a 409. */
        @Test
        void aContestedSeatReturns409NamingTheSeats() throws Exception {
            TestScenario scenario = testData.createBookableShow();
            hold(scenario, scenario.premiumSeatIds());

            mockMvc.perform(post(ApiEndpoints.Holds.ROOT)
                            .with(httpBasic(scenario.otherCustomerUsername(), TestDataFactory.PASSWORD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(holdRequest(scenario, scenario.premiumSeatIds())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SEAT_ALREADY_HELD.name()))
                    .andExpect(jsonPath("$.details.heldSeats").isArray());
        }

        @Test
        void aDeclinedPaymentReturns402() throws Exception {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse booking = hold(scenario, scenario.premiumSeatIds());

            mockMvc.perform(post(ApiEndpoints.Bookings.PAYMENT, booking.bookingId())
                            .with(customer(scenario))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "paymentMethodToken", PaymentMethodToken.FAILURE.token(),
                                    "idempotencyKey", "api-decline-" + UUID.randomUUID()))))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PAYMENT_FAILED.name()));
        }

        @Test
        void aSuccessfulPaymentReturns200AndConfirms() throws Exception {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse booking = hold(scenario, scenario.premiumSeatIds());

            mockMvc.perform(post(ApiEndpoints.Bookings.PAYMENT, booking.bookingId())
                            .with(customer(scenario))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "paymentMethodToken", PaymentMethodToken.SUCCESS.token(),
                                    "idempotencyKey", "api-ok-" + UUID.randomUUID()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.confirmedAt").exists());
        }

        @Test
        void cancellationReturns200WithTheRefundBreakdown() throws Exception {
            TestScenario scenario = testData.createBookableShow();
            BookingResponse booking = hold(scenario, scenario.premiumSeatIds());
            mockMvc.perform(post(ApiEndpoints.Bookings.PAYMENT, booking.bookingId())
                            .with(customer(scenario))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "paymentMethodToken", PaymentMethodToken.SUCCESS.token(),
                                    "idempotencyKey", "api-cancel-" + UUID.randomUUID()))))
                    .andExpect(status().isOk());

            mockMvc.perform(post(ApiEndpoints.Bookings.CANCEL, booking.bookingId()).with(customer(scenario)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.booking.status").value("REFUNDED"))
                    .andExpect(jsonPath("$.refund.refundPercentage").value(100.00))
                    .andExpect(jsonPath("$.refund.status").value("COMPLETED"));
        }

        /**
         * The internals of the hold - the token the confirmation transaction
         * re-verifies against - must never reach a client.
         */
        @Test
        void responsesNeverLeakTheHoldToken() throws Exception {
            TestScenario scenario = testData.createBookableShow();

            String body = mockMvc.perform(post(ApiEndpoints.Holds.ROOT)
                            .with(customer(scenario))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(holdRequest(scenario, scenario.premiumSeatIds())))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(body).doesNotContain("holdToken");
        }
    }

    @Nested
    @DisplayName("seat map")
    class SeatMap {

        @Test
        void theSeatMapCarriesPricesAndEffectiveAvailability() throws Exception {
            TestScenario scenario = testData.createBookableShow();
            hold(scenario, scenario.premiumSeatIds());

            mockMvc.perform(get(ApiEndpoints.Shows.SEATS, scenario.showId()).with(customer(scenario)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalSeats").value(6))
                    .andExpect(jsonPath("$.availableSeats").value(4))
                    .andExpect(jsonPath("$.dayType").value("WEEKDAY"))
                    .andExpect(jsonPath("$.seats[0].price").exists())
                    .andExpect(jsonPath("$.seats[0].label").exists());
        }
    }

    // -----------------------------------------------------------------------

    private BookingResponse hold(TestScenario scenario, List<UUID> seatIds) {
        return seatHoldService.createHold(
                new CreateHoldCommand(scenario.showId(), seatIds, null, scenario.customerId()));
    }

    private String holdRequest(TestScenario scenario, List<UUID> seatIds) {
        return json(Map.of(
                "showId", scenario.showId().toString(),
                "seatIds", seatIds.stream().map(UUID::toString).toList()));
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer(
            TestScenario scenario) {
        return httpBasic(scenario.customerUsername(), TestDataFactory.PASSWORD);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin(
            TestScenario scenario) {
        return httpBasic(scenario.adminUsername(), TestDataFactory.PASSWORD);
    }
}
