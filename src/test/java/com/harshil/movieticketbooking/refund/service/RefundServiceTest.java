package com.harshil.movieticketbooking.refund.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harshil.movieticketbooking.booking.domain.Booking;
import com.harshil.movieticketbooking.city.domain.City;
import com.harshil.movieticketbooking.movie.domain.Movie;
import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import com.harshil.movieticketbooking.refund.domain.RefundPolicyRule;
import com.harshil.movieticketbooking.refund.repository.RefundPolicyRepository;
import com.harshil.movieticketbooking.refund.repository.RefundRepository;
import com.harshil.movieticketbooking.show.domain.Show;
import com.harshil.movieticketbooking.show.domain.ShowStatus;
import com.harshil.movieticketbooking.theater.domain.Screen;
import com.harshil.movieticketbooking.theater.domain.ScreenType;
import com.harshil.movieticketbooking.theater.domain.Theater;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Policy <em>resolution</em> - which ladder applies to a given show.
 * <p>
 * The arithmetic of applying a ladder lives in {@link RefundCalculatorTest};
 * a real calculator is wired in here so the two are exercised together
 * exactly as they are in production.
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-09T09:00:00Z");
    private static final BigDecimal PAID = new BigDecimal("800.00");

    @Mock
    private RefundPolicyRepository refundPolicyRepository;

    @Mock
    private RefundRepository refundRepository;

    private final RefundCalculator refundCalculator = new RefundCalculator();

    private RefundService refundService() {
        return new RefundService(refundPolicyRepository, refundRepository, refundCalculator);
    }

    @Test
    void theTheaterPolicyWinsOverThePlatformDefault() {
        Booking booking = bookingStartingIn(Duration.ofHours(6));
        RefundPolicy theaterPolicy = policy("Theater Flexible", band(0, 2, "0.00"), band(2, null, "100.00"));
        when(refundPolicyRepository.findActiveByTheaterId(booking.getShow().getScreen().getTheater().getId()))
                .thenReturn(Optional.of(theaterPolicy));

        RefundAssessment assessment = refundService().assess(booking, PAID, NOW);

        assertThat(assessment.policy()).isSameAs(theaterPolicy);
        assertThat(assessment.percentage()).isEqualByComparingTo("100.00");
        assertThat(assessment.refundAmount()).isEqualByComparingTo("800.00");
        verify(refundPolicyRepository, never()).findActiveDefault();
    }

    @Test
    void thePlatformDefaultAppliesWhenTheTheaterHasNoPolicy() {
        Booking booking = bookingStartingIn(Duration.ofHours(6));
        RefundPolicy standard = policy("Standard",
                band(0, 12, "0.00"), band(12, 24, "50.00"), band(24, null, "100.00"));
        when(refundPolicyRepository.findActiveByTheaterId(booking.getShow().getScreen().getTheater().getId()))
                .thenReturn(Optional.empty());
        when(refundPolicyRepository.findActiveDefault()).thenReturn(Optional.of(standard));

        RefundAssessment assessment = refundService().assess(booking, PAID, NOW);

        assertThat(assessment.policy()).isSameAs(standard);
        assertThat(assessment.percentage()).isEqualByComparingTo("0.00");
        assertThat(assessment.isRefundable()).isFalse();
    }

    /** No policy configured at all must mean no refund, never a full one. */
    @Test
    void noPolicyAnywhereMeansNoRefund() {
        Booking booking = bookingStartingIn(Duration.ofHours(72));
        when(refundPolicyRepository.findActiveByTheaterId(booking.getShow().getScreen().getTheater().getId()))
                .thenReturn(Optional.empty());
        when(refundPolicyRepository.findActiveDefault()).thenReturn(Optional.empty());

        RefundAssessment assessment = refundService().assess(booking, PAID, NOW);

        assertThat(assessment.refundAmount()).isEqualByComparingTo("0.00");
        assertThat(assessment.policy()).isNull();
    }

    /**
     * The assessment is based on the amount actually charged, which is what
     * the caller passes in - not on the booking's own total. The two can only
     * differ if something went wrong, and money that moved is the honest basis
     * for money moving back.
     */
    @Test
    void theAssessmentIsBasedOnTheAmountActuallyPaid() {
        Booking booking = bookingStartingIn(Duration.ofHours(72));
        when(refundPolicyRepository.findActiveByTheaterId(booking.getShow().getScreen().getTheater().getId()))
                .thenReturn(Optional.of(policy("Full", band(0, null, "100.00"))));

        RefundAssessment assessment = refundService().assess(booking, new BigDecimal("123.45"), NOW);

        assertThat(assessment.originalAmount()).isEqualByComparingTo("123.45");
        assertThat(assessment.refundAmount()).isEqualByComparingTo("123.45");
    }

    // -----------------------------------------------------------------------

    private static RefundPolicy policy(String name, RefundPolicyRule... bands) {
        RefundPolicy policy = RefundPolicy.builder().name(name).active(true).build();
        for (RefundPolicyRule band : bands) {
            policy.addRule(band);
        }
        return policy;
    }

    private static RefundPolicyRule band(int minHours, Integer maxHours, String percentage) {
        return RefundPolicyRule.builder()
                .minHoursBeforeShow(minHours)
                .maxHoursBeforeShow(maxHours)
                .refundPercentage(new BigDecimal(percentage))
                .build();
    }

    private static Booking bookingStartingIn(Duration untilShow) {
        City city = City.builder()
                .name("Test").state("TS").country("Testland").timeZone("Asia/Kolkata").active(true).build();
        Theater theater = Theater.builder()
                .city(city).name("Test Theater").address("1 Road").active(true).build();
        Screen screen = Screen.builder()
                .theater(theater).name("Screen 1").screenType(ScreenType.STANDARD).active(true).build();
        Instant startsAt = NOW.plus(untilShow);
        Show show = Show.builder()
                .screen(screen)
                .movie(Movie.builder().title("Fixture").language("English").durationMinutes(120).active(true).build())
                .startsAt(startsAt)
                .endsAt(startsAt.plusSeconds(7200))
                .status(ShowStatus.SCHEDULED)
                .build();
        return Booking.builder()
                .bookingReference("MTB-REFUNDTEST")
                .show(show)
                .seatCount(2)
                .subtotalAmount(PAID)
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(PAID)
                .currency("INR")
                .build();
    }
}
