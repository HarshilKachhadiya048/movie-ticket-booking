package com.harshil.movieticketbooking.pricing.dto;

import com.harshil.movieticketbooking.pricing.domain.DayType;
import com.harshil.movieticketbooking.pricing.domain.PricingRule;
import com.harshil.movieticketbooking.pricing.domain.PricingScope;
import com.harshil.movieticketbooking.seat.domain.SeatCategory;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A pricing rule and the level it applies at.
 * <p>
 * {@code scope} is returned explicitly so an admin can see at a glance which
 * rule would win for a given seat, without having to work it out from which
 * id happens to be non-null.
 */
public record PricingRuleResponse(
        UUID pricingRuleId,
        PricingScope scope,
        UUID cityId,
        UUID theaterId,
        UUID screenId,
        SeatCategory seatCategory,
        DayType dayType,
        BigDecimal price,
        boolean active) {

    public static PricingRuleResponse from(PricingRule rule) {
        return new PricingRuleResponse(
                rule.getId(),
                rule.scope(),
                rule.getCity() == null ? null : rule.getCity().getId(),
                rule.getTheater() == null ? null : rule.getTheater().getId(),
                rule.getScreen() == null ? null : rule.getScreen().getId(),
                rule.getSeatCategory(),
                rule.getDayType(),
                rule.getPrice(),
                rule.isActive());
    }
}
