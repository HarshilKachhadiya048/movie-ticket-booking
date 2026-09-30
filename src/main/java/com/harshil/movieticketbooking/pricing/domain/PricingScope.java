package com.harshil.movieticketbooking.pricing.domain;

/**
 * How narrowly a {@link PricingRule} applies. When more than one rule matches
 * a seat, the one with the highest {@link #specificity()} wins.
 */
public enum PricingScope {

    GLOBAL(0),
    CITY(1),
    THEATER(2),
    SCREEN(3);

    private final int specificity;

    PricingScope(int specificity) {
        this.specificity = specificity;
    }

    public int specificity() {
        return specificity;
    }
}
