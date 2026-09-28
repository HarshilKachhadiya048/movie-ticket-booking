package com.harshil.movieticketbooking.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DayTypeTest {

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            "2026-03-09, WEEKDAY",
            "2026-03-10, WEEKDAY",
            "2026-03-11, WEEKDAY",
            "2026-03-12, WEEKDAY",
            "2026-03-13, WEEKDAY",
            "2026-03-14, WEEKEND",
            "2026-03-15, WEEKEND"
    })
    void saturdayAndSundayAreTheWeekend(LocalDate date, DayType expected) {
        assertThat(DayType.of(date)).isEqualTo(expected);
    }

    /**
     * The same instant can be a weekday in one zone and a weekend in another,
     * which is why {@code DayType.of} takes an already-localised date rather
     * than an instant: the caller has to say whose calendar it is asking about.
     */
    @Test
    void theSameInstantCanBelongToDifferentDayTypes() {
        Instant instant = Instant.parse("2026-03-13T19:00:00Z");

        LocalDate inUtc = instant.atZone(ZoneId.of("UTC")).toLocalDate();
        LocalDate inKolkata = instant.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();

        assertThat(DayType.of(inUtc)).isEqualTo(DayType.WEEKDAY);
        assertThat(DayType.of(inKolkata)).isEqualTo(DayType.WEEKEND);
    }
}
