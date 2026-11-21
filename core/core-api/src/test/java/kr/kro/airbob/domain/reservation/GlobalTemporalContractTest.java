package kr.kro.airbob.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationLocalTimeException;
import kr.kro.airbob.domain.reservation.inventory.AccommodationInventorySeedPolicy;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationStayPricePolicy;

class GlobalTemporalContractTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource("kr.kro.airbob.domain.reservation.GlobalTemporalCases#stays")
	void fixedStayInstantsLocalBookingInventoryAndPriceRemainConsistent(GlobalTemporalCases.Stay expected) {
		Accommodation accommodation = Accommodation.builder().timeZoneId(expected.zone())
			.checkInTime(expected.checkInTime()).checkOutTime(expected.checkOutTime()).build();
		Clock clock = Clock.fixed(expected.decisionAt(), ZoneOffset.UTC);
		BookingWindowProvider provider = new BookingWindowProvider(clock);
		AccommodationInventorySeedPolicy seedPolicy = new AccommodationInventorySeedPolicy(provider, 7);

		assertThat(Reservation.resolveCheckInAt(accommodation, expected.checkIn())).isEqualTo(expected.checkInAt());
		assertThat(Reservation.resolveCheckOutAt(accommodation, expected.checkOut())).isEqualTo(expected.checkOutAt());
		assertThat(provider.currentFor(expected.zone())).isEqualTo(
			new BookingWindow(expected.localToday(), expected.bookingEnd()));
		assertThat(seedPolicy.currentRange(expected.zone(), expected.decisionAt())).isEqualTo(
			new AccommodationInventorySeedPolicy.SeedRange(expected.localToday(), expected.seedEnd()));
		assertThat(provider.eligibleTimeZonesForStay(expected.checkIn(), expected.checkOut())).contains(expected.zone());
		var price = ReservationStayPricePolicy.calculate(120_000, expected.checkIn(), expected.checkOut());
		assertThat(price.nights()).isEqualTo(expected.nights());
		assertThat(price.subtotal()).isEqualTo(120_000 * expected.nights());
		assertThat(Duration.between(expected.checkInAt(), expected.checkOutAt()).toMinutes() / 60.0)
			.isEqualTo(expected.elapsedHours());
	}

	@Test
	void newYorkSpringGapIsRejectedForBothCheckInAndCheckOut() {
		Accommodation accommodation = Accommodation.builder().timeZoneId("America/New_York")
			.checkInTime(LocalTime.of(2, 30)).checkOutTime(LocalTime.of(2, 30)).build();
		LocalDate gap = LocalDate.of(2026, 3, 8);

		assertThatThrownBy(() -> Reservation.resolveCheckInAt(accommodation, gap))
			.isInstanceOf(InvalidReservationLocalTimeException.class);
		assertThatThrownBy(() -> Reservation.resolveCheckOutAt(accommodation, gap))
			.isInstanceOf(InvalidReservationLocalTimeException.class);
	}

	@Test
	void localDateDifferenceChangesSearchEligibilityAtTheThreeMonthBoundary() {
		BookingWindowProvider provider = new BookingWindowProvider(
			Clock.fixed(Instant.parse("2026-08-12T01:00:00Z"), ZoneOffset.UTC));

		assertThat(provider.eligibleTimeZonesForStay(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 12)))
			.contains("Asia/Seoul").doesNotContain("America/New_York");
	}

	@Test
	void postChangeVancouverLocalTodayOpensTheCorrectFutureSearchBoundary() {
		BookingWindowProvider provider = new BookingWindowProvider(
			Clock.fixed(Instant.parse("2026-11-15T07:30:00Z"), ZoneOffset.UTC));

		assertThat(provider.eligibleTimeZonesForStay(LocalDate.of(2027, 2, 14), LocalDate.of(2027, 2, 15)))
			.contains("America/Vancouver").doesNotContain("America/Los_Angeles");
	}

	@ParameterizedTest
	@CsvSource({
		"2026-01-31, 2026-04-30",
		"2023-11-30, 2024-02-29",
		"2024-11-30, 2025-02-28",
		"2024-02-29, 2024-05-29",
		"2026-08-31, 2026-11-30"
	})
	void bookingWindowUsesCalendarMonthsAtMonthEndAndLeapYear(LocalDate start, LocalDate expectedEnd) {
		BookingWindow window = BookingWindow.startingOn(start);

		assertThat(window.endExclusive()).isEqualTo(expectedEnd);
		assertThat(window.containsStay(expectedEnd.minusDays(1), expectedEnd)).isTrue();
		assertThat(window.containsStay(expectedEnd, expectedEnd.plusDays(1))).isFalse();
	}
}
