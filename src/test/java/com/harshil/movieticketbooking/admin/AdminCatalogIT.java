package com.harshil.movieticketbooking.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.support.AbstractIntegrationTest;
import com.harshil.movieticketbooking.support.TestDataFactory;
import com.harshil.movieticketbooking.user.domain.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/**
 * The admin API, exercised the way an operator actually would: build a venue
 * from nothing, price it, schedule a show, and confirm a customer can then
 * book it.
 * <p>
 * This is the test that would catch a broken admin endpoint before it silently
 * made the customer side unusable - a screen with no seat layout, or a show
 * with no pricing, produces a perfectly valid-looking catalogue entry that
 * cannot be booked.
 */
class AdminCatalogIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private RequestPostProcessor admin;
    private RequestPostProcessor customer;

    @Test
    @DisplayName("an admin can build a bookable show from an empty catalogue")
    void anAdminCanBuildACompleteBookableVenue() throws Exception {
        setUpAccounts();

        UUID cityId = createCity();
        UUID movieId = createMovie();
        UUID theaterId = createTheater(cityId);
        UUID screenId = createScreen(theaterId);
        List<UUID> seatIds = createSeatLayout(screenId);
        createPricingRules();
        UUID showId = createShow(screenId, movieId);

        // The customer's view of everything the admin just built.
        mockMvc.perform(get(ApiEndpoints.Cities.THEATERS, cityId).with(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].theaterId").value(theaterId.toString()));

        mockMvc.perform(get(ApiEndpoints.Theaters.SHOWS, theaterId).with(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].showId").value(showId.toString()))
                .andExpect(jsonPath("$[0].availableSeats").value(seatIds.size()));

        mockMvc.perform(get(ApiEndpoints.Shows.SEATS, showId).with(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(seatIds.size()))
                .andExpect(jsonPath("$.seats[0].price").value(200.00));

        // And it is genuinely bookable, not merely visible.
        mockMvc.perform(post(ApiEndpoints.Holds.ROOT)
                        .with(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "showId", showId.toString(),
                                "seatIds", List.of(seatIds.getFirst().toString())))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(200.00));
    }

    @Test
    @DisplayName("a screen with no seat layout cannot have a show scheduled on it")
    void schedulingAShowOnAnEmptyScreenIsRejected() throws Exception {
        setUpAccounts();
        UUID cityId = createCity();
        UUID movieId = createMovie();
        UUID screenId = createScreen(createTheater(cityId));

        mockMvc.perform(post(ApiEndpoints.Admin.Shows.ROOT)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "screenId", screenId.toString(),
                                "movieId", movieId.toString(),
                                "startsAt", Instant.now(clock).plus(Duration.ofDays(2)).toString()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.SCREEN_HAS_NO_SEATS.name()));
    }

    @Test
    @DisplayName("a second show cannot overlap an existing one on the same screen")
    void overlappingShowsOnOneScreenAreRejected() throws Exception {
        setUpAccounts();
        UUID cityId = createCity();
        UUID movieId = createMovie();
        UUID screenId = createScreen(createTheater(cityId));
        createSeatLayout(screenId);
        createPricingRules();

        Instant startsAt = Instant.now(clock).plus(Duration.ofDays(2));
        mockMvc.perform(post(ApiEndpoints.Admin.Shows.ROOT)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "screenId", screenId.toString(),
                                "movieId", movieId.toString(),
                                "startsAt", startsAt.toString()))))
                .andExpect(status().isCreated());

        mockMvc.perform(post(ApiEndpoints.Admin.Shows.ROOT)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "screenId", screenId.toString(),
                                "movieId", movieId.toString(),
                                "startsAt", startsAt.plus(Duration.ofMinutes(30)).toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOW_OVERLAPS_EXISTING.name()));
    }

    @Test
    @DisplayName("a seat layout may not be created twice on the same screen")
    void aSecondSeatLayoutIsRejected() throws Exception {
        setUpAccounts();
        UUID screenId = createScreen(createTheater(createCity()));
        createSeatLayout(screenId);

        mockMvc.perform(post(ApiEndpoints.Admin.Screens.SEATS, screenId)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rows", List.of(
                                Map.of("rowLabel", "Z", "seatCount", 5, "category", "REGULAR"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.SEAT_LAYOUT_NOT_EMPTY.name()));
    }

    @Test
    @DisplayName("overlapping refund bands are rejected")
    void overlappingRefundBandsAreRejected() throws Exception {
        setUpAccounts();

        mockMvc.perform(post(ApiEndpoints.Admin.RefundPolicies.ROOT)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Overlapping " + UUID.randomUUID(),
                                "rules", List.of(
                                        Map.of("minHoursBeforeShow", 0, "maxHoursBeforeShow", 24,
                                                "refundPercentage", 0),
                                        Map.of("minHoursBeforeShow", 12, "maxHoursBeforeShow", 36,
                                                "refundPercentage", 50))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.REFUND_POLICY_BANDS_OVERLAP.name()));
    }

    @Test
    @DisplayName("a valid refund ladder is accepted and returned in order")
    void aValidRefundLadderIsAccepted() throws Exception {
        setUpAccounts();

        mockMvc.perform(post(ApiEndpoints.Admin.RefundPolicies.ROOT)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Ladder " + UUID.randomUUID(),
                                "rules", List.of(
                                        Map.of("minHoursBeforeShow", 24, "refundPercentage", 100),
                                        Map.of("minHoursBeforeShow", 0, "maxHoursBeforeShow", 12,
                                                "refundPercentage", 0),
                                        Map.of("minHoursBeforeShow", 12, "maxHoursBeforeShow", 24,
                                                "refundPercentage", 50))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bands.length()").value(3))
                .andExpect(jsonPath("$.bands[0].minHoursBeforeShow").value(0))
                .andExpect(jsonPath("$.bands[2].maxHoursBeforeShow").doesNotExist());
    }

    @Test
    @DisplayName("an invalid IANA time zone is rejected with a helpful message")
    void anInvalidTimeZoneIsRejected() throws Exception {
        setUpAccounts();

        mockMvc.perform(post(ApiEndpoints.Admin.Cities.ROOT)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Nowhere", "state", "None", "country", "Nowhereland",
                                "timeZone", "Mars/Olympus_Mons"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.name()))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("time zone")));
    }

    // -----------------------------------------------------------------------

    private void setUpAccounts() {
        String adminUsername = "cat-admin-" + UUID.randomUUID();
        String customerUsername = "cat-customer-" + UUID.randomUUID();
        testData.saveUser(adminUsername, Role.ADMIN);
        testData.saveUser(customerUsername, Role.CUSTOMER);
        admin = httpBasic(adminUsername, TestDataFactory.PASSWORD);
        customer = httpBasic(customerUsername, TestDataFactory.PASSWORD);
    }

    private UUID createCity() throws Exception {
        return idFrom(post(ApiEndpoints.Admin.Cities.ROOT), "cityId", Map.of(
                "name", "Catalogue City " + UUID.randomUUID(),
                "state", "Test State",
                "country", "Testland",
                "timeZone", "Asia/Kolkata"));
    }

    private UUID createMovie() throws Exception {
        return idFrom(post(ApiEndpoints.Admin.Movies.ROOT), "movieId", Map.of(
                "title", "Admin Feature " + UUID.randomUUID(),
                "language", "English",
                "certification", "UA",
                "durationMinutes", 120));
    }

    private UUID createTheater(UUID cityId) throws Exception {
        return idFrom(post(ApiEndpoints.Admin.Theaters.ROOT), "theaterId", Map.of(
                "cityId", cityId.toString(),
                "name", "Catalogue Theater " + UUID.randomUUID(),
                "address", "1 Admin Road"));
    }

    private UUID createScreen(UUID theaterId) throws Exception {
        return idFrom(post(ApiEndpoints.Admin.Screens.ROOT), "screenId", Map.of(
                "theaterId", theaterId.toString(),
                "name", "Screen " + UUID.randomUUID(),
                "screenType", "STANDARD"));
    }

    private List<UUID> createSeatLayout(UUID screenId) throws Exception {
        String body = mockMvc.perform(post(ApiEndpoints.Admin.Screens.SEATS, screenId)
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("rows", List.of(
                                Map.of("rowLabel", "A", "seatCount", 4, "category", "REGULAR"))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body)
                .valueStream()
                .map(node -> UUID.fromString(node.get("seatId").asString()))
                .toList();
    }

    private void createPricingRules() throws Exception {
        for (String dayType : List.of("WEEKDAY", "WEEKEND")) {
            mockMvc.perform(post(ApiEndpoints.Admin.PricingRules.ROOT)
                            .with(admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "seatCategory", "REGULAR",
                                    "dayType", dayType,
                                    "price", 200.00))))
                    .andExpect(status().isCreated());
        }
    }

    private UUID createShow(UUID screenId, UUID movieId) throws Exception {
        return idFrom(post(ApiEndpoints.Admin.Shows.ROOT), "showId", Map.of(
                "screenId", screenId.toString(),
                "movieId", movieId.toString(),
                "startsAt", Instant.now(clock).plus(Duration.ofDays(2)).toString()));
    }

    private UUID idFrom(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String idField,
            Map<String, Object> body) throws Exception {
        String response = mockMvc.perform(request
                        .with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(response);
        return UUID.fromString(node.get(idField).asString());
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
