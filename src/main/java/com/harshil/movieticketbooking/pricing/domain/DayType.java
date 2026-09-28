package com.harshil.movieticketbooking.pricing.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Whether a show is priced at weekday or weekend rates.
 * <p>
 * Derived from the show's <em>local</em> calendar date, which is why the
 * caller must supply a {@link LocalDate} already projected into the city's
 * time zone. A 00:30 Saturday show in Mumbai is 19:00 Friday in UTC; pricing
 * it off the UTC date would quietly undercharge for weekend screenings.
 */
public enum DayType {

    WEEKDAY,
    WEEKEND;

    public static DayType of(LocalDate localDate) {
        DayOfWeek dayOfWeek = localDate.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
                ? WEEKEND
                : WEEKDAY;
    }
}
