package com.harshil.movieticketbooking.theater.domain;

/**
 * Projection format of a screen.
 * <p>
 * Descriptive only: it does not participate in pricing. Price is resolved from
 * {@code pricing_rules}, which can be scoped to a specific screen, so a
 * premium format is priced by configuring a rule for that screen rather than
 * by branching on this enum in code.
 */
public enum ScreenType {

    STANDARD,
    IMAX,
    FOUR_DX,
    RECLINER
}
