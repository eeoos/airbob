package kr.kro.airbob.domain.reservation.policy;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("예약 가능 기간 테스트")
class BookingWindowTest {

	@Test
	@DisplayName("예약 가능 기간은 시작일로부터 3개월인 반열린 구간이다")
	void bookingWindowIsThreeMonthHalfOpenRange() {
		BookingWindow bookingWindow = BookingWindow.startingOn(LocalDate.of(2026, 1, 31));

		assertThat(bookingWindow.startInclusive()).isEqualTo(LocalDate.of(2026, 1, 31));
		assertThat(bookingWindow.endExclusive()).isEqualTo(LocalDate.of(2026, 4, 30));
	}

	@Test
	@DisplayName("체크아웃은 종료 경계와 같을 수 있지만 체크인은 종료 경계부터 시작할 수 없다")
	void allowsCheckoutAtEndBoundaryOnly() {
		BookingWindow bookingWindow = BookingWindow.startingOn(LocalDate.of(2026, 8, 1));

		assertThat(bookingWindow.containsStay(
			LocalDate.of(2026, 10, 31),
			LocalDate.of(2026, 11, 1)))
			.isTrue();
		assertThat(bookingWindow.containsStay(
			LocalDate.of(2026, 11, 1),
			LocalDate.of(2026, 11, 2)))
			.isFalse();
		assertThat(bookingWindow.containsStay(
			LocalDate.of(2026, 10, 31),
			LocalDate.of(2026, 11, 2)))
			.isFalse();
		assertThat(bookingWindow.containsStay(
			LocalDate.of(2026, 8, 2),
			LocalDate.of(2026, 8, 2)))
			.isFalse();
	}
}
