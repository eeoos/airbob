package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;

@DisplayName("AccommodationInventorySeedPolicy")
class AccommodationInventorySeedPolicyTest {

	private static final Instant NOW = Instant.parse("2026-08-25T15:30:00Z");

	@Test
	@DisplayName("숙소 현지 booking window 전체와 설정된 안전 buffer를 seed 범위로 만든다")
	void addsSafetyBufferToTheLocalBookingWindow() {
		BookingWindowProvider windowProvider = new BookingWindowProvider(
			Clock.fixed(NOW, ZoneOffset.UTC));
		AccommodationInventorySeedPolicy policy =
			new AccommodationInventorySeedPolicy(windowProvider, 2);

		AccommodationInventorySeedPolicy.SeedRange range =
			policy.currentRange("Asia/Seoul", NOW);

		assertThat(range.startInclusive()).isEqualTo(LocalDate.of(2026, 8, 26));
		assertThat(range.endExclusive()).isEqualTo(LocalDate.of(2026, 11, 28));
	}

	@Test
	@DisplayName("seed buffer는 음수일 수 없다")
	void rejectsNegativeBuffer() {
		BookingWindowProvider windowProvider = new BookingWindowProvider(
			Clock.fixed(NOW, ZoneOffset.UTC));

		assertThatThrownBy(() -> new AccommodationInventorySeedPolicy(windowProvider, -1))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
