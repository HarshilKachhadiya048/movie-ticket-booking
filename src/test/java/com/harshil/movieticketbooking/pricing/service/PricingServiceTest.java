package com.harshil.movieticketbooking.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.movie.domain.Movie;
import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.pricing.domain.PricingRule;
import com.harshil.movieticketbooking.pricing.repository.PricingRuleRepository;
import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowStatus;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.ScreenType;
import com.harshil.movieticketbooking.theater.domain.Theater;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Rule resolution: which price wins, and when.
 * <p>
 * The repository is mocked because what is under test is the <em>choosing</em>,
 * not the querying - given a set of candidate rules, does the most specific
 * one win, and is the day type decided in the right time zone.
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    /** 2026-03-14T18:00Z is Saturday 23:30 in Asia/Kolkata - a weekend locally. */
    private static final Instant SATURDAY_EVENING_IST = Instant.parse("2026-03-14T18:00:00Z");

    /** 2026-03-13T19:00Z is Saturday 00:30 in Asia/Kolkata but Friday in UTC. */
    private static final Instant FRIDAY_UTC_SATURDAY_IST = Instant.parse("2026-03-13T19:00:00Z");

    /** 2026-03-12T09:00Z is Thursday everywhere relevant. */
    private static final Instant THURSDAY = Instant.parse("2026-03-12T09:00:00Z");

    @Mock
    private PricingRuleRepository pricingRuleRepository;

    @InjectMocks
    private PricingService pricingService;

    @Nested
    @DisplayName("scope specificity")
    class ScopeSpecificity {

        /**
         * The whole point of the scope cascade: a screen-level rule beats a
         * theater rule, which beats a city rule, which beats the global
         * default - all for the same seat category and day type.
         */
        @Test
        void mostSpecificScopeWins() {
            Fixture fixture = new Fixture(THURSDAY);
            when(pricingRuleRepository.findCandidateRules(any(), any(), any(), eq(DayType.WEEKDAY)))
                    .thenReturn(List.of(
                            globalRule(SeatCategory.PREMIUM, DayType.WEEKDAY, "350.00"),
                            cityRule(fixture.city, SeatCategory.PREMIUM, DayType.WEEKDAY, "420.00"),
                            theaterRule(fixture.theater, SeatCategory.PREMIUM, DayType.WEEKDAY, "500.00"),
                            screenRule(fixture.screen, SeatCategory.PREMIUM, DayType.WEEKDAY, "600.00")));

            PriceQuote quote = pricingService.quote(fixture.show, List.of(fixture.premiumSeat));

            assertThat(quote.seats()).singleElement()
                    .extracting(PriceQuote.PricedSeat::price, org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                    .isEqualByComparingTo("600.00");
        }

        @Test
        void fallsBackToTheGlobalRuleWhenNothingNarrowerExists() {
            Fixture fixture = new Fixture(THURSDAY);
            when(pricingRuleRepository.findCandidateRules(any(), any(), any(), eq(DayType.WEEKDAY)))
                    .thenReturn(List.of(globalRule(SeatCategory.PREMIUM, DayType.WEEKDAY, "350.00")));

            PriceQuote quote = pricingService.quote(fixture.show, List.of(fixture.premiumSeat));

            assertThat(quote.subtotal()).isEqualByComparingTo("350.00");
        }

        @Test
        void differentCategoriesResolveIndependently() {
            Fixture fixture = new Fixture(THURSDAY);
            when(pricingRuleRepository.findCandidateRules(any(), any(), any(), eq(DayType.WEEKDAY)))
                    .thenReturn(List.of(
                            globalRule(SeatCategory.REGULAR, DayType.WEEKDAY, "200.00"),
                            screenRule(fixture.screen, SeatCategory.PREMIUM, DayType.WEEKDAY, "600.00")));

            PriceQuote quote = pricingService.quote(
                    fixture.show, List.of(fixture.premiumSeat, fixture.regularSeat));

            assertThat(quote.subtotal()).isEqualByComparingTo("800.00");
            assertThat(quote.seats()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("day type is a local question")
    class LocalDayType {

        @Test
        void saturdayInTheCityZoneIsAWeekend() {
            Fixture fixture = new Fixture(SATURDAY_EVENING_IST);
            assertThat(pricingService.resolveDayType(fixture.show)).isEqualTo(DayType.WEEKEND);
        }

        /**
         * The case that makes the time zone column earn its keep. This instant
         * is Friday in UTC but Saturday 00:30 in Asia/Kolkata. Pricing off the
         * UTC date would charge weekday rates for a weekend screening.
         */
        @Test
        void aShowThatIsFridayInUtcButSaturdayLocallyIsAWeekend() {
            Fixture fixture = new Fixture(FRIDAY_UTC_SATURDAY_IST);

            assertThat(FRIDAY_UTC_SATURDAY_IST.atZone(java.time.ZoneId.of("UTC")).getDayOfWeek())
                    .isEqualTo(java.time.DayOfWeek.FRIDAY);
            assertThat(pricingService.resolveDayType(fixture.show)).isEqualTo(DayType.WEEKEND);
        }

        @Test
        void weekendRulesAreUsedForWeekendShows() {
            Fixture fixture = new Fixture(SATURDAY_EVENING_IST);
            when(pricingRuleRepository.findCandidateRules(any(), any(), any(), eq(DayType.WEEKEND)))
                    .thenReturn(List.of(globalRule(SeatCategory.PREMIUM, DayType.WEEKEND, "450.00")));

            PriceQuote quote = pricingService.quote(fixture.show, List.of(fixture.premiumSeat));

            assertThat(quote.dayType()).isEqualTo(DayType.WEEKEND);
            assertThat(quote.subtotal()).isEqualByComparingTo("450.00");
        }
    }

    /**
     * Incomplete configuration must refuse the booking rather than default to
     * zero. Handing out free tickets because an admin forgot a rule is worse
     * than a failed request.
     */
    @Test
    void aSeatWithNoMatchingRuleIsRejected() {
        Fixture fixture = new Fixture(THURSDAY);
        when(pricingRuleRepository.findCandidateRules(any(), any(), any(), eq(DayType.WEEKDAY)))
                .thenReturn(List.of(globalRule(SeatCategory.REGULAR, DayType.WEEKDAY, "200.00")));

        assertThatThrownBy(() -> pricingService.quote(fixture.show, List.of(fixture.premiumSeat)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(ErrorCode.PRICING_RULE_NOT_FOUND);
    }

    @Test
    void subtotalIsTheSumOfEverySeat() {
        Fixture fixture = new Fixture(THURSDAY);
        when(pricingRuleRepository.findCandidateRules(any(), any(), any(), eq(DayType.WEEKDAY)))
                .thenReturn(List.of(globalRule(SeatCategory.REGULAR, DayType.WEEKDAY, "199.99")));

        PriceQuote quote = pricingService.quote(
                fixture.show, List.of(fixture.regularSeat, fixture.regularSeat, fixture.regularSeat));

        assertThat(quote.subtotal()).isEqualByComparingTo("599.97");
    }

    // -----------------------------------------------------------------------

    private static PricingRule globalRule(SeatCategory category, DayType dayType, String price) {
        return PricingRule.builder()
                .seatCategory(category).dayType(dayType).price(new BigDecimal(price)).active(true).build();
    }

    private static PricingRule cityRule(City city, SeatCategory category, DayType dayType, String price) {
        return PricingRule.builder().city(city)
                .seatCategory(category).dayType(dayType).price(new BigDecimal(price)).active(true).build();
    }

    private static PricingRule theaterRule(Theater theater, SeatCategory category, DayType dayType, String price) {
        return PricingRule.builder().theater(theater)
                .seatCategory(category).dayType(dayType).price(new BigDecimal(price)).active(true).build();
    }

    private static PricingRule screenRule(Screen screen, SeatCategory category, DayType dayType, String price) {
        return PricingRule.builder().screen(screen)
                .seatCategory(category).dayType(dayType).price(new BigDecimal(price)).active(true).build();
    }

    /** A venue chain in Asia/Kolkata with one premium and one regular seat. */
    private static final class Fixture {

        private final City city;
        private final Theater theater;
        private final Screen screen;
        private final Show show;
        private final Seat premiumSeat;
        private final Seat regularSeat;

        private Fixture(Instant showStartsAt) {
            city = City.builder()
                    .name("Mumbai").state("MH").country("India").timeZone("Asia/Kolkata").active(true).build();
            theater = Theater.builder()
                    .city(city).name("Marine Drive").address("12 Marine Drive").active(true).build();
            screen = Screen.builder()
                    .theater(theater).name("Screen 1").screenType(ScreenType.IMAX).active(true).build();
            premiumSeat = Seat.builder()
                    .screen(screen).rowLabel("A").seatNumber(1).category(SeatCategory.PREMIUM).active(true).build();
            regularSeat = Seat.builder()
                    .screen(screen).rowLabel("D").seatNumber(1).category(SeatCategory.REGULAR).active(true).build();
            show = Show.builder()
                    .screen(screen)
                    .movie(Movie.builder().title("Fixture").language("English").durationMinutes(120).active(true).build())
                    .startsAt(showStartsAt)
                    .endsAt(showStartsAt.plusSeconds(7200))
                    .status(ShowStatus.SCHEDULED)
                    .build();
        }
    }
}
