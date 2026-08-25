package kr.kro.airbob.domain.reservation.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;

@DisplayName("예약 숙박 가격 정책 테스트")
class ReservationStayPricePolicyTest {

	@Test
	@DisplayName("숙박 요금은 1박 단가와 숙박일 수의 정확한 곱으로 계산한다")
	void calculatesExactStayPrice() {
		ReservationStayPricePolicy.StayPrice price = ReservationStayPricePolicy.calculate(
			120_000L,
			LocalDate.of(2026, 9, 10),
			LocalDate.of(2026, 9, 13)
		);

		assertThat(price.nightlyPrice()).isEqualTo(120_000L);
		assertThat(price.nights()).isEqualTo(3L);
		assertThat(price.subtotal()).isEqualTo(360_000L);
	}

	@Test
	@DisplayName("체크아웃 날짜가 체크인 날짜와 같거나 앞서면 가격을 만들지 않는다")
	void rejectsNonPositiveStayLength() {
		LocalDate checkIn = LocalDate.of(2026, 9, 10);

		assertThatThrownBy(() -> ReservationStayPricePolicy.calculate(120_000L, checkIn, checkIn))
			.isInstanceOf(InvalidReservationDateException.class);
		assertThatThrownBy(() -> ReservationStayPricePolicy.calculate(
			120_000L, checkIn, checkIn.minusDays(1)))
			.isInstanceOf(InvalidReservationDateException.class);
	}

	@Test
	@DisplayName("숙박 요금의 long 범위를 넘는 계산은 조용히 오버플로하지 않는다")
	void rejectsSubtotalOverflow() {
		assertThatThrownBy(() -> ReservationStayPricePolicy.calculate(
			Long.MAX_VALUE,
			LocalDate.of(2026, 9, 10),
			LocalDate.of(2026, 9, 12)
		)).isInstanceOf(ArithmeticException.class);
	}
}
