package kr.kro.airbob.core.domain.reservation;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.member.Member;

@DisplayName("ReservationReady 테스트")
class ReservationReadyTest {
	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

	private Reservation pendingReservation;

	@BeforeEach
	void setUp() {
		LocalDate checkInDate = LocalDate.of(2026, 8, 13);
		Accommodation accommodation = Accommodation.builder()
			.id(1L)
			.name("Test Accommodation")
			.basePrice(100_000L)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.build();
		Member guest = Member.builder()
			.id(1L)
			.email("guest@test.com")
			.nickname("TestGuest")
			.build();
		pendingReservation = Reservation.builder()
			.id(1L)
			.reservationUid(UUID.randomUUID())
			.reservationCode("ABC123")
			.accommodation(accommodation)
			.guest(guest)
			.checkInDate(checkInDate)
			.checkOutDate(checkInDate.plusDays(2))
			.guestCount(2)
			.status(ReservationStatus.PAYMENT_PENDING)
			.totalPrice(200_000L)
			.discountAmount(0L)
			.currency("KRW")
			.expiresAt(NOW.plusSeconds(15 * 60))
			.build();
	}

	@Test
	@DisplayName("결제 대기 예약은 조회 시각 기준 결제 가능 여부와 홀드 만료 시각을 담는다")
	void capturesPendingReservationAtServerTime() {
		ReservationReady result = ReservationReady.of(pendingReservation, NOW);

		assertThat(result.reservationUid()).isEqualTo(pendingReservation.getReservationUid());
		assertThat(result.amount()).isEqualTo(pendingReservation.getTotalPrice());
		assertThat(result.status()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		assertThat(result.paymentRequired()).isTrue();
		assertThat(result.paymentAllowed()).isTrue();
		assertThat(result.serverTime()).isEqualTo(NOW);
		assertThat(result.holdExpiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
		assertThat(result.customerEmail()).isEqualTo("guest@test.com");
		assertThat(result.customerName()).isEqualTo("TestGuest");
	}

	@Test
	@DisplayName("0원 확정 예약은 결제가 필요 없고 홀드 만료 시각을 담지 않는다")
	void capturesComplimentaryReservation() {
		Reservation complimentary = Reservation.builder()
			.id(pendingReservation.getId())
			.reservationUid(pendingReservation.getReservationUid())
			.accommodation(pendingReservation.getAccommodation())
			.guest(pendingReservation.getGuest())
			.status(ReservationStatus.CONFIRMED)
			.totalPrice(0L)
			.discountAmount(200_000L)
			.currency("KRW")
			.expiresAt(NOW.plusSeconds(15 * 60))
			.build();

		ReservationReady result = ReservationReady.of(complimentary, NOW);

		assertThat(result.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(result.paymentRequired()).isFalse();
		assertThat(result.paymentAllowed()).isFalse();
		assertThat(result.holdExpiresAt()).isNull();
		assertThat(result.subtotal()).isEqualTo(200_000L);
		assertThat(result.discountAmount()).isEqualTo(200_000L);
	}
}
