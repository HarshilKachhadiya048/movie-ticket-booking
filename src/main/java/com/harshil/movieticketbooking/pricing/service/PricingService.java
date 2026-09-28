package com.harshil.movieticketbooking.pricing.service;

import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.pricing.domain.PricingRule;
import com.harshil.movieticketbooking.pricing.repository.PricingRuleRepository;
import com.harshil.movieticketbooking.seat.domain.Seat;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.Theater;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves what a set of seats costs for a given show.
 * <p>
 * <b>No prices in code.</b> Every amount comes from {@code pricing_rules}.
 * There is no base price constant, no weekend multiplier and no
 * {@code if (category == PREMIUM)} anywhere in this class - a seat's price is
 * the {@code price} column of the rule that matches it.
 * <p>
 * <b>Resolution.</b> A rule matches a seat when its seat category and day type
 * match. Several rules can match at once, scoped at screen, theater, city or
 * global level; the most specific wins. That gives a platform-wide default
 * that any level can override, purely as data.
 * <p>
 * <b>Weekend is a local question.</b> The day type comes from the show's start
 * instant projected into the <em>city's</em> time zone, not the server's and
 * not UTC. A 00:30 Saturday show in Mumbai is 19:00 Friday in UTC; pricing it
 * off UTC would charge weekday rates for a weekend screening.
 * <p>
 * <b>Failing closed.</b> A seat with no matching rule raises
 * {@link ErrorCode#PRICING_RULE_NOT_FOUND} rather than defaulting to zero or to
 * some fallback price. Handing out free tickets because configuration is
 * incomplete is worse than refusing the booking.
 * <p>
 * Read-only and side-effect free: the caller snapshots the result onto
 * {@code booking_seats}, which is what protects historical bookings from later
 * price changes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    private final PricingRuleRepository pricingRuleRepository;

    /**
     * Prices every seat in {@code seats} for {@code show}.
     *
     * @param show  must have its screen, theater and city loaded - see
     *              {@code ShowRepository.findByIdWithVenueAndMovie}
     * @param seats the seats being booked, in request order
     * @throws DomainException {@link ErrorCode#PRICING_RULE_NOT_FOUND} if any
     *                         seat has no applicable rule
     */
    @Transactional(readOnly = true)
    public PriceQuote quote(Show show, List<Seat> seats) {
        DayType dayType = resolveDayType(show);
        Map<SeatCategory, PricingRule> rulesByCategory = resolveRules(show, dayType);

        List<PriceQuote.PricedSeat> pricedSeats = seats.stream()
                .map(seat -> new PriceQuote.PricedSeat(seat, priceFor(seat, rulesByCategory, dayType)))
                .toList();

        PriceQuote quote = PriceQuote.of(dayType, pricedSeats);
        log.debug("Priced {} seat(s) for show {} as {} ({} subtotal {})",
                seats.size(), show.getId(), dayType, quote.seats().size(), quote.subtotal());
        return quote;
    }

    /** The day type a show is priced at, in the city's own calendar. */
    @Transactional(readOnly = true)
    public DayType resolveDayType(Show show) {
        City city = show.getScreen().getTheater().getCity();
        return DayType.of(show.localDate(city.zoneId()));
    }

    /**
     * The winning rule per seat category for this show, from a single query.
     * <p>
     * All candidate rules across the four scope levels are fetched at once and
     * reduced here, so pricing a ten-seat booking still costs one query.
     */
    private Map<SeatCategory, PricingRule> resolveRules(Show show, DayType dayType) {
        Screen screen = show.getScreen();
        Theater theater = screen.getTheater();
        City city = theater.getCity();

        List<PricingRule> candidates = pricingRuleRepository.findCandidateRules(
                screen.getId(), theater.getId(), city.getId(), dayType);

        Map<SeatCategory, PricingRule> mostSpecific = new EnumMap<>(SeatCategory.class);
        for (PricingRule candidate : candidates) {
            mostSpecific.merge(candidate.getSeatCategory(), candidate, PricingService::moreSpecific);
        }
        return mostSpecific;
    }

    private static PricingRule moreSpecific(PricingRule left, PricingRule right) {
        return right.scope().specificity() > left.scope().specificity() ? right : left;
    }

    private BigDecimal priceFor(Seat seat, Map<SeatCategory, PricingRule> rulesByCategory, DayType dayType) {
        PricingRule rule = rulesByCategory.get(seat.getCategory());
        if (rule == null) {
            throw new DomainException(
                    ErrorCode.PRICING_RULE_NOT_FOUND,
                    "No active pricing rule for a %s seat on a %s".formatted(seat.getCategory(), dayType))
                    .withDetail("seatId", seat.getId())
                    .withDetail("seatCategory", seat.getCategory())
                    .withDetail("dayType", dayType);
        }
        return Money.normalize(rule.getPrice());
    }
}
