package kr.kro.airbob.domain.reservation.policy;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("숙소 현지 날짜 기준 예약 가능 기간 테스트")
class BookingWindowProviderTest {

	@Test
	@DisplayName("같은 UTC 시각도 숙소 시간대에 따라 서로 다른 시작일의 예약 가능 기간을 계산한다")
	void calculatesBookingWindowFromAccommodationLocalDate() {
		Clock clock = Clock.fixed(Instant.parse("2026-08-12T01:00:00Z"), ZoneOffset.UTC);
		BookingWindowProvider provider = new BookingWindowProvider(clock);

		assertThat(provider.currentFor("Asia/Seoul"))
			.isEqualTo(new BookingWindow(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 11, 12)));
		assertThat(provider.currentFor("America/New_York"))
			.isEqualTo(new BookingWindow(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 11, 11)));
	}
}
