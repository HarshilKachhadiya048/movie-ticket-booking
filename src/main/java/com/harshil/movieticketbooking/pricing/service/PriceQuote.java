package com.harshil.movieticketbooking.pricing.service;

import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.seat.domain.Seat;
import java.math.BigDecimal;
import java.util.List;

/**
 * What {@link PricingService} resolved for one set of seats on one show.
 *
 * @param dayType  the day type the whole show was priced at, decided in the
 *                 city's local time zone
 * @param seats    per-seat prices, in the order they were requested
 * @param subtotal the sum, before any discount
 */
public record PriceQuote(DayType dayType, List<PricedSeat> seats, BigDecimal subtotal) {

    public static PriceQuote of(DayType dayType, List<PricedSeat> seats) {
        BigDecimal subtotal = seats.stream()
                .map(PricedSeat::price)
                .reduce(Money.ZERO, Money::sum);
        return new PriceQuote(dayType, List.copyOf(seats), subtotal);
    }

    /** One seat and the price resolved for it. */
    public record PricedSeat(Seat seat, BigDecimal price) {
    }
}
