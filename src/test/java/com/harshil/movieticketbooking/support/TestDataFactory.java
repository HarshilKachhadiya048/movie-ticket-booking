package com.harshil.movieticketbooking.support;

import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.city.repository.CityRepository;
import com.harshil.movieticketbooking.discount.domain.DiscountCode;
import com.harshil.movieticketbooking.discount.domain.DiscountType;
import com.harshil.movieticketbooking.discount.repository.DiscountCodeRepository;
import com.harshil.movieticketbooking.movie.domain.Movie;
import com.harshil.movieticketbooking.movie.repository.MovieRepository;
import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.pricing.domain.PricingRule;
import com.harshil.movieticketbooking.pricing.repository.PricingRuleRepository;
import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import com.harshil.movieticketbooking.refund.domain.RefundPolicyRule;
import com.harshil.movieticketbooking.refund.repository.RefundPolicyRepository;
import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import com.harshil.movieticketbooking.seat.repository.SeatRepository;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowSeat;
import com.harshil.movieticketbooking.show.domain.ShowStatus;
import com.harshil.movieticketbooking.show.repository.ShowRepository;
import com.harshil.movieticketbooking.show.repository.ShowSeatRepository;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.ScreenType;
import com.harshil.movieticketbooking.theater.domain.Theater;
import com.harshil.movieticketbooking.theater.repository.ScreenRepository;
import com.harshil.movieticketbooking.theater.repository.TheaterRepository;
import com.harshil.movieticketbooking.user.domain.Role;
import com.harshil.movieticketbooking.user.domain.User;
import com.harshil.movieticketbooking.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the fixtures integration tests run against.
 * <p>
 * Tests construct their own world rather than leaning on {@code db/seed},
 * which the test profile deliberately excludes. Two reasons: a test that
 * depends on demo data breaks whenever somebody edits the demo data, and a
 * test whose fixtures are visible in the test file is far easier to read than
 * one whose preconditions live in a SQL file three directories away.
 * <p>
 * Show times are derived from the injected {@link Clock}, so they stay
 * consistent with whatever instant the test has set.
 */
@TestComponent
@RequiredArgsConstructor
public class TestDataFactory {

    public static final String PASSWORD = "Password@123";

    public static final BigDecimal REGULAR_WEEKDAY_PRICE = new BigDecimal("200.00");
    public static final BigDecimal REGULAR_WEEKEND_PRICE = new BigDecimal("260.00");
    public static final BigDecimal PREMIUM_WEEKDAY_PRICE = new BigDecimal("350.00");
    public static final BigDecimal PREMIUM_WEEKEND_PRICE = new BigDecimal("450.00");

    /** Two premium seats in row A, four regular in row B. */
    private static final int PREMIUM_SEATS = 2;
    private static final int REGULAR_SEATS = 4;

    private final UserRepository userRepository;
    private final CityRepository cityRepository;
    private final MovieRepository movieRepository;
    private final TheaterRepository theaterRepository;
    private final ScreenRepository screenRepository;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final ShowSeatRepository showSeatRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final RefundPolicyRepository refundPolicyRepository;
    private final DiscountCodeRepository discountCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /** A show 48 hours out - comfortably inside the 100% refund band. */
    @Transactional
    public TestScenario createBookableShow() {
        return createBookableShow(Instant.now(clock).plus(Duration.ofHours(48)));
    }

    /**
     * A complete world: two customers, an admin, a venue with a six-seat
     * screen, global pricing for both tiers and day types, the default refund
     * ladder, and one scheduled show with materialised inventory.
     */
    @Transactional
    public TestScenario createBookableShow(Instant startsAt) {
        User admin = saveUser("admin-" + shortId(), Role.ADMIN);
        User customer = saveUser("customer-" + shortId(), Role.CUSTOMER);
        User otherCustomer = saveUser("other-" + shortId(), Role.CUSTOMER);

        City city = cityRepository.save(City.builder()
                .name("Testville-" + shortId())
                .state("Test State")
                .country("Testland")
                .timeZone("Asia/Kolkata")
                .active(true)
                .build());

        Movie movie = movieRepository.save(Movie.builder()
                .title("Fixture Feature " + shortId())
                .language("English")
                .certification("UA")
                .durationMinutes(120)
                .active(true)
                .build());

        Theater theater = theaterRepository.save(Theater.builder()
                .city(city)
                .name("Fixture Theater " + shortId())
                .address("1 Fixture Road")
                .active(true)
                .build());

        Screen screen = screenRepository.save(Screen.builder()
                .theater(theater)
                .name("Screen 1")
                .screenType(ScreenType.STANDARD)
                .active(true)
                .build());

        List<Seat> premiumSeats = saveSeats(screen, "A", PREMIUM_SEATS, SeatCategory.PREMIUM);
        List<Seat> regularSeats = saveSeats(screen, "B", REGULAR_SEATS, SeatCategory.REGULAR);

        createGlobalPricing();
        RefundPolicy refundPolicy = createDefaultRefundPolicy();

        Show show = showRepository.save(Show.builder()
                .screen(screen)
                .movie(movie)
                .startsAt(startsAt)
                .endsAt(startsAt.plus(Duration.ofMinutes(140)))
                .status(ShowStatus.SCHEDULED)
                .build());

        List<Seat> allSeats = new ArrayList<>(premiumSeats);
        allSeats.addAll(regularSeats);
        showSeatRepository.saveAll(allSeats.stream().map(seat -> ShowSeat.available(show, seat)).toList());

        return new TestScenario(
                admin.getId(),
                admin.getUsername(),
                customer.getId(),
                customer.getUsername(),
                otherCustomer.getId(),
                otherCustomer.getUsername(),
                city.getId(),
                movie.getId(),
                theater.getId(),
                screen.getId(),
                show.getId(),
                startsAt,
                allSeats.stream().map(Seat::getId).toList(),
                premiumSeats.stream().map(Seat::getId).toList(),
                regularSeats.stream().map(Seat::getId).toList(),
                refundPolicy.getId());
    }

    /**
     * A screen with enough seats for the concurrency test to contend on a
     * single row while still having room for multi-seat cases.
     */
    @Transactional
    public User saveUser(String username, Role role) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.test")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Fixture " + username)
                .phoneNumber("+910000000000")
                .role(role)
                .enabled(true)
                .build());
    }

    @Transactional
    public DiscountCode createDiscountCode(
            String code,
            DiscountType type,
            BigDecimal value,
            BigDecimal maxDiscount,
            BigDecimal minBooking,
            Integer usageLimit,
            Integer perUserLimit) {
        Instant now = Instant.now(clock);
        return discountCodeRepository.save(DiscountCode.builder()
                .code(code)
                .description("Fixture discount " + code)
                .discountType(type)
                .discountValue(value)
                .maxDiscountAmount(maxDiscount)
                .minBookingAmount(minBooking == null ? BigDecimal.ZERO : minBooking)
                .validFrom(now.minus(Duration.ofDays(1)))
                .validUntil(now.plus(Duration.ofDays(30)))
                .usageLimit(usageLimit)
                .perUserLimit(perUserLimit)
                .usedCount(0)
                .active(true)
                .build());
    }

    /** Attaches a more generous policy to one theater, overriding the default. */
    @Transactional
    public RefundPolicy createTheaterRefundPolicy(UUID theaterId, int fullRefundFromHours) {
        Theater theater = theaterRepository.findById(theaterId).orElseThrow();
        RefundPolicy policy = RefundPolicy.builder()
                .name("Theater Policy " + shortId())
                .description("Full refund from %dh out".formatted(fullRefundFromHours))
                .theater(theater)
                .defaultPolicy(false)
                .active(true)
                .build();
        policy.addRule(band(0, fullRefundFromHours, "0.00"));
        policy.addRule(band(fullRefundFromHours, null, "100.00"));
        return refundPolicyRepository.save(policy);
    }

    // -----------------------------------------------------------------------

    private List<Seat> saveSeats(Screen screen, String rowLabel, int count, SeatCategory category) {
        List<Seat> seats = new ArrayList<>(count);
        for (int seatNumber = 1; seatNumber <= count; seatNumber++) {
            seats.add(Seat.builder()
                    .screen(screen)
                    .rowLabel(rowLabel)
                    .seatNumber(seatNumber)
                    .category(category)
                    .active(true)
                    .build());
        }
        return seatRepository.saveAll(seats);
    }

    /**
     * Global rules only. Tests that care about the scope cascade add narrower
     * rules themselves, so the default fixture stays easy to reason about.
     */
    private void createGlobalPricing() {
        if (!pricingRuleRepository.findAllWithScope().isEmpty()) {
            return;
        }
        pricingRuleRepository.saveAll(List.of(
                globalRule(SeatCategory.REGULAR, DayType.WEEKDAY, REGULAR_WEEKDAY_PRICE),
                globalRule(SeatCategory.REGULAR, DayType.WEEKEND, REGULAR_WEEKEND_PRICE),
                globalRule(SeatCategory.PREMIUM, DayType.WEEKDAY, PREMIUM_WEEKDAY_PRICE),
                globalRule(SeatCategory.PREMIUM, DayType.WEEKEND, PREMIUM_WEEKEND_PRICE)));
    }

    private static PricingRule globalRule(SeatCategory category, DayType dayType, BigDecimal price) {
        return PricingRule.builder()
                .seatCategory(category)
                .dayType(dayType)
                .price(price)
                .active(true)
                .build();
    }

    /** The brief's example ladder: 0% under 12h, 50% to 24h, 100% beyond. */
    private RefundPolicy createDefaultRefundPolicy() {
        return refundPolicyRepository.findActiveDefault().orElseGet(() -> {
            RefundPolicy policy = RefundPolicy.builder()
                    .name("Fixture Standard")
                    .description("0% under 12h, 50% to 24h, 100% beyond")
                    .defaultPolicy(true)
                    .active(true)
                    .build();
            policy.addRule(band(0, 12, "0.00"));
            policy.addRule(band(12, 24, "50.00"));
            policy.addRule(band(24, null, "100.00"));
            return refundPolicyRepository.save(policy);
        });
    }

    private static RefundPolicyRule band(int minHours, Integer maxHours, String percentage) {
        return RefundPolicyRule.builder()
                .minHoursBeforeShow(minHours)
                .maxHoursBeforeShow(maxHours)
                .refundPercentage(new BigDecimal(percentage))
                .build();
    }

    /** Keeps fixture names unique without making them unreadable. */
    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
