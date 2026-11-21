package kr.kro.airbob.domain.reservation.policy;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

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

	@Test
	@DisplayName("같은 숙박일도 현지 3개월 예약 창 안에 있는 시간대만 반환한다")
	void findsEligibleTimeZonesForStay() {
		Clock clock = Clock.fixed(Instant.parse("2026-08-12T00:30:00Z"), ZoneOffset.UTC);
		BookingWindowProvider provider = new BookingWindowProvider(clock);

		var eligibleTimeZones = provider.eligibleTimeZonesForStay(
			LocalDate.of(2026, 11, 11),
			LocalDate.of(2026, 11, 12)
		);

		assertThat(eligibleTimeZones)
			.contains("Asia/Seoul")
			.doesNotContain("America/New_York");
	}

	@Test
	@DisplayName("전체 시간대의 예약 가능 여부는 동일한 기준 시각으로 계산한다")
	void usesSingleInstantForAllTimeZones() {
		AtomicInteger instantCalls = new AtomicInteger();
		Instant now = Instant.parse("2026-08-12T00:30:00Z");
		Clock clock = new Clock() {
			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				instantCalls.incrementAndGet();
				return now;
			}
		};
		BookingWindowProvider provider = new BookingWindowProvider(clock);

		provider.eligibleTimeZonesForStay(
			LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 21));

		assertThat(instantCalls).hasValue(1);
	}

	@Test
	@DisplayName("색인 범위는 전 세계 현지 날짜의 3개월 예약 창을 포함하도록 UTC 날짜 양쪽에 하루를 둔다")
	void calculatesGlobalSafeIndexingWindow() {
		Clock clock = Clock.fixed(Instant.parse("2026-08-31T23:30:00Z"), ZoneOffset.UTC);
		BookingWindowProvider provider = new BookingWindowProvider(clock);

		ReservationIndexingWindow window = provider.currentIndexingWindow();

		assertThat(window).isEqualTo(new ReservationIndexingWindow(
			LocalDate.of(2026, 8, 30),
			LocalDate.of(2026, 12, 1)
		));
	}
}
