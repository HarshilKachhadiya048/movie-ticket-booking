package com.harshil.movieticketbooking.pricing.service;

import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.city.repository.CityRepository;
import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.common.money.Money;
import com.harshil.movieticketbooking.pricing.domain.PricingRule;
import com.harshil.movieticketbooking.pricing.dto.PricingRuleRequest;
import com.harshil.movieticketbooking.pricing.dto.PricingRuleResponse;
import com.harshil.movieticketbooking.pricing.repository.PricingRuleRepository;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.Theater;
import com.harshil.movieticketbooking.theater.service.ScreenService;
import com.harshil.movieticketbooking.theater.service.TheaterService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of pricing rules.
 * <p>
 * Editing a rule only affects future bookings. Existing ones carry their own
 * price snapshot on {@code booking_seats}, so nothing a price change does here
 * can retroactively alter what a customer was charged.
 */
@Service
@RequiredArgsConstructor
public class PricingRuleService {

    private final PricingRuleRepository pricingRuleRepository;
    private final CityRepository cityRepository;
    private final TheaterService theaterService;
    private final ScreenService screenService;

    @Transactional(readOnly = true)
    public List<PricingRuleResponse> listRules() {
        return pricingRuleRepository.findAllWithScope()
                .stream()
                .map(PricingRuleResponse::from)
                .toList();
    }

    @Transactional
    public PricingRuleResponse createRule(PricingRuleRequest request) {
        validateSingleScope(request);
        requireNoDuplicateScope(request, null);

        PricingRule rule = pricingRuleRepository.save(PricingRule.builder()
                .city(resolveCity(request.cityId()))
                .theater(resolveTheater(request.theaterId()))
                .screen(resolveScreen(request.screenId()))
                .seatCategory(request.seatCategory())
                .dayType(request.dayType())
                .price(Money.normalize(request.price()))
                .active(request.activeOrDefault())
                .build());
        return PricingRuleResponse.from(rule);
    }

    /**
     * Only price and activation can change. A rule's scope, seat category and
     * day type are its identity - changing those is creating a different rule,
     * and doing it in place would silently re-point whatever the old rule was
     * covering.
     */
    @Transactional
    public PricingRuleResponse updateRule(UUID pricingRuleId, PricingRuleRequest request) {
        PricingRule rule = pricingRuleRepository.findById(pricingRuleId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.PRICING_RULE_NOT_FOUND,
                        "Pricing rule %s does not exist".formatted(pricingRuleId)));

        rule.updatePrice(Money.normalize(request.price()), request.activeOrDefault());
        return PricingRuleResponse.from(rule);
    }

    @Transactional
    public void deleteRule(UUID pricingRuleId) {
        if (!pricingRuleRepository.existsById(pricingRuleId)) {
            throw new DomainException(
                    ErrorCode.PRICING_RULE_NOT_FOUND,
                    "Pricing rule %s does not exist".formatted(pricingRuleId));
        }
        // Safe to delete outright: nothing references a pricing rule. Bookings
        // keep their own price snapshot rather than pointing back here.
        pricingRuleRepository.deleteById(pricingRuleId);
    }

    // -----------------------------------------------------------------------

    private static void validateSingleScope(PricingRuleRequest request) {
        if (request.scopeCount() > 1) {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST,
                    "A pricing rule may be scoped to at most one of cityId, theaterId or screenId");
        }
    }

    private void requireNoDuplicateScope(PricingRuleRequest request, UUID excludedId) {
        boolean exists = pricingRuleRepository.existsForScope(
                request.cityId(),
                request.theaterId(),
                request.screenId(),
                request.seatCategory(),
                request.dayType(),
                excludedId);
        if (exists) {
            throw new DomainException(
                    ErrorCode.PRICING_RULE_ALREADY_EXISTS,
                    "A %s / %s rule already exists at that scope".formatted(
                            request.seatCategory(), request.dayType()));
        }
    }

    private City resolveCity(UUID cityId) {
        if (cityId == null) {
            return null;
        }
        return cityRepository.findById(cityId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.CITY_NOT_FOUND, "City %s does not exist".formatted(cityId)));
    }

    private Theater resolveTheater(UUID theaterId) {
        return theaterId == null ? null : theaterService.requireTheaterWithCity(theaterId);
    }

    private Screen resolveScreen(UUID screenId) {
        return screenId == null ? null : screenService.requireScreenWithVenue(screenId);
    }
}
