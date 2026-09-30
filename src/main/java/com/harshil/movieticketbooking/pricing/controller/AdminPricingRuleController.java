package com.harshil.movieticketbooking.pricing.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.pricing.dto.PricingRuleRequest;
import com.harshil.movieticketbooking.pricing.dto.PricingRuleResponse;
import com.harshil.movieticketbooking.pricing.service.PricingRuleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pricing administration.
 * <p>
 * Changes here affect future bookings only: existing bookings carry their own
 * price snapshot, so re-pricing a screen cannot alter what anyone was already
 * charged.
 */
@RestController
@RequiredArgsConstructor
public class AdminPricingRuleController {

    private final PricingRuleService pricingRuleService;

    @GetMapping(ApiEndpoints.Admin.PricingRules.ROOT)
    public List<PricingRuleResponse> listRules() {
        return pricingRuleService.listRules();
    }

    @PostMapping(ApiEndpoints.Admin.PricingRules.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public PricingRuleResponse createRule(@Valid @RequestBody PricingRuleRequest request) {
        return pricingRuleService.createRule(request);
    }

    /** Only price and activation change; scope and keys are the rule's identity. */
    @PutMapping(ApiEndpoints.Admin.PricingRules.BY_ID)
    public PricingRuleResponse updateRule(
            @PathVariable UUID pricingRuleId,
            @Valid @RequestBody PricingRuleRequest request) {
        return pricingRuleService.updateRule(pricingRuleId, request);
    }

    @DeleteMapping(ApiEndpoints.Admin.PricingRules.BY_ID)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID pricingRuleId) {
        pricingRuleService.deleteRule(pricingRuleId);
    }
}
