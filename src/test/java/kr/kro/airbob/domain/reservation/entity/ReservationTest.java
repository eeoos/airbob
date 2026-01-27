package kr.kro.airbob.domain.reservation.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationStatusException;

@DisplayName("Reservation 엔티티 테스트")
class ReservationTest {

	private Accommodation accommodation;
	private Member guest;

	@BeforeEach
	void setUp() {
		accommodation = Accommodation.builder()
			.id(1L)
			.basePrice(100_000L)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.build();

		guest = Member.builder()
			.id(1L)
			.email("guest@test.com")
			.nickname("TestGuest")
			.build();
	}

	@Nested
	@DisplayName("가격 계산 테스트")
	class PriceCalculationTest {

		@Test
		@DisplayName("3박 숙박 시 basePrice * 3으로 계산된다")
		void 정상_3박_가격_계산() {
			// given
			LocalDate checkInDate = LocalDate.of(2025, 1, 26);
			LocalDate checkOutDate = LocalDate.of(2025, 1, 29);
			ReservationRequest.Create request = new ReservationRequest.Create(1L, checkInDate, checkOutDate, 2);

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123");

			// then
			assertThat(reservation.getTotalPrice()).isEqualTo(300_000L);
		}

		@Test
		@DisplayName("1박 최소 숙박 시 basePrice * 1로 계산된다")
		void 정상_1박_최소_가격_계산() {
			// given
			LocalDate checkInDate = LocalDate.of(2025, 1, 26);
			LocalDate checkOutDate = LocalDate.of(2025, 1, 27);
			ReservationRequest.Create request = new ReservationRequest.Create(1L, checkInDate, checkOutDate, 2);

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123");

			// then
			assertThat(reservation.getTotalPrice()).isEqualTo(100_000L);
		}

		@Test
		@DisplayName("30박 장기 숙박 시 basePrice * 30으로 계산된다")
		void 경계값_30박_장기숙박() {
			// given
			LocalDate checkInDate = LocalDate.of(2025, 1, 1);
			LocalDate checkOutDate = LocalDate.of(2025, 1, 31);
			ReservationRequest.Create request = new ReservationRequest.Create(1L, checkInDate, checkOutDate, 2);

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123");

			// then
			assertThat(reservation.getTotalPrice()).isEqualTo(3_000_000L);
		}

		@Test
		@DisplayName("체크인과 체크아웃이 같은 날짜면 InvalidReservationDateException이 발생한다")
		void 예외_당일_체크아웃() {
			// given
			LocalDate sameDate = LocalDate.of(2025, 1, 26);
			ReservationRequest.Create request = new ReservationRequest.Create(1L, sameDate, sameDate, 2);

			// when & then
			assertThatThrownBy(() -> Reservation.createPendingReservation(accommodation, guest, request, "ABC123"))
				.isInstanceOf(InvalidReservationDateException.class)
				.extracting(e -> ((InvalidReservationDateException)e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_RESERVATION_DATE);
		}

		@Test
		@DisplayName("체크아웃이 체크인보다 빠르면 InvalidReservationDateException이 발생한다")
		void 예외_체크아웃이_체크인보다_빠름() {
			// given
			LocalDate checkInDate = LocalDate.of(2025, 1, 28);
			LocalDate checkOutDate = LocalDate.of(2025, 1, 26);
			ReservationRequest.Create request = new ReservationRequest.Create(1L, checkInDate, checkOutDate, 2);

			// when & then
			assertThatThrownBy(() -> Reservation.createPendingReservation(accommodation, guest, request, "ABC123"))
				.isInstanceOf(InvalidReservationDateException.class);
		}
	}

	@Nested
	@DisplayName("상태 전이 테스트")
	class StatusTransitionTest {

		@Test
		@DisplayName("PAYMENT_PENDING 상태에서 confirm() 호출 시 CONFIRMED로 변경된다")
		void 정상_결제대기에서_확정() {
			// given
			Reservation reservation = createPendingReservation();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);

			// when
			reservation.confirm();

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		}

		@Test
		@DisplayName("PAYMENT_PENDING 상태에서 expire() 호출 시 EXPIRED로 변경된다")
		void 정상_결제대기에서_만료() {
			// given
			Reservation reservation = createPendingReservation();

			// when
			reservation.expire();

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		}

		@Test
		@DisplayName("CONFIRMED 상태에서 cancel() 호출 시 CANCELLED로 변경된다")
		void 정상_확정에서_취소() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.confirm();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

			// when
			reservation.cancel();

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		}

		@Test
		@DisplayName("CANCELLED 상태에서 failCancellation() 호출 시 CANCELLATION_FAILED로 변경된다")
		void 정상_취소에서_취소실패() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.confirm();
			reservation.cancel();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

			// when
			reservation.failCancellation();

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
		}

		@Test
		@DisplayName("CONFIRMED 상태에서 expire() 호출 시 InvalidReservationStatusException이 발생한다")
		void 예외_확정상태에서_만료시도() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.confirm();

			// when & then
			assertThatThrownBy(reservation::expire)
				.isInstanceOf(InvalidReservationStatusException.class)
				.extracting(e -> ((InvalidReservationStatusException)e).getErrorCode())
				.isEqualTo(ErrorCode.CANNOT_EXPIRE_RESERVATION);
		}

		@Test
		@DisplayName("EXPIRED 상태에서 confirm() 호출 시 InvalidReservationStatusException이 발생한다")
		void 예외_만료상태에서_확정시도() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.expire();

			// when & then
			assertThatThrownBy(reservation::confirm)
				.isInstanceOf(InvalidReservationStatusException.class)
				.extracting(e -> ((InvalidReservationStatusException)e).getErrorCode())
				.isEqualTo(ErrorCode.CANNOT_CONFIRM_RESERVATION);
		}

		@Test
		@DisplayName("PAYMENT_PENDING 상태에서 cancel() 호출 시 InvalidReservationStatusException이 발생한다")
		void 예외_결제대기에서_취소시도() {
			// given
			Reservation reservation = createPendingReservation();

			// when & then
			assertThatThrownBy(reservation::cancel)
				.isInstanceOf(InvalidReservationStatusException.class)
				.extracting(e -> ((InvalidReservationStatusException)e).getErrorCode())
				.isEqualTo(ErrorCode.CANNOT_CANCEL_RESERVATION);
		}

		@Test
		@DisplayName("CANCELLED 상태에서 confirm() 호출 시 InvalidReservationStatusException이 발생한다")
		void 예외_취소상태에서_확정시도() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.confirm();
			reservation.cancel();

			// when & then
			assertThatThrownBy(reservation::confirm)
				.isInstanceOf(InvalidReservationStatusException.class)
				.extracting(e -> ((InvalidReservationStatusException)e).getErrorCode())
				.isEqualTo(ErrorCode.CANNOT_CONFIRM_RESERVATION);
		}

		@Test
		@DisplayName("CANCELLATION_FAILED 상태에서 failCancellation() 호출 시 상태가 유지된다 (멱등성)")
		void 멱등성_이미_취소실패_상태() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.confirm();
			reservation.cancel();
			reservation.failCancellation();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);

			// when - 같은 메서드 재호출
			reservation.failCancellation();

			// then - 상태 유지 (예외 없음)
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
		}

		@Test
		@DisplayName("CONFIRMED가 아닌 상태에서 failCancellation() 호출 시 상태가 유지된다")
		void 멱등성_PAYMENT_PENDING에서_failCancellation_호출시_상태유지() {
			// given
			Reservation reservation = createPendingReservation();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);

			// when - CANCELLED가 아닌 상태에서 호출
			reservation.failCancellation();

			// then - 상태 유지 (예외 없음)
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		}
	}

	@Nested
	@DisplayName("팩토리 메서드 테스트")
	class FactoryMethodTest {

		@Test
		@DisplayName("createPendingReservation 호출 시 PAYMENT_PENDING 상태로 생성된다")
		void 정상_예약_생성_상태확인() {
			// given
			ReservationRequest.Create request = createValidRequest();

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123");

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 체크인 시간이 숙소의 checkInTime과 병합된다")
		void 체크인시간_병합() {
			// given
			LocalDate checkInDate = LocalDate.of(2025, 1, 26);
			LocalDate checkOutDate = LocalDate.of(2025, 1, 28);
			ReservationRequest.Create request = new ReservationRequest.Create(1L, checkInDate, checkOutDate, 2);

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123");

			// then
			LocalDateTime expectedCheckIn = LocalDateTime.of(2025, 1, 26, 15, 0);
			LocalDateTime expectedCheckOut = LocalDateTime.of(2025, 1, 28, 11, 0);

			assertThat(reservation.getCheckIn()).isEqualTo(expectedCheckIn);
			assertThat(reservation.getCheckOut()).isEqualTo(expectedCheckOut);
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 만료시간이 15분 후로 설정된다")
		void 만료시간_설정() {
			// given
			ReservationRequest.Create request = createValidRequest();
			LocalDateTime beforeCreation = LocalDateTime.now();

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123");

			// then
			LocalDateTime afterCreation = LocalDateTime.now();
			assertThat(reservation.getExpiresAt())
				.isAfterOrEqualTo(beforeCreation.plusMinutes(15))
				.isBeforeOrEqualTo(afterCreation.plusMinutes(15).plusSeconds(1));
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 예약코드가 설정된다")
		void 예약코드_설정() {
			// given
			ReservationRequest.Create request = createValidRequest();
			String reservationCode = "XYZ789";

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, reservationCode);

			// then
			assertThat(reservation.getReservationCode()).isEqualTo(reservationCode);
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 통화가 KRW로 설정된다")
		void 통화_설정() {
			// given
			ReservationRequest.Create request = createValidRequest();

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123");

			// then
			assertThat(reservation.getCurrency()).isEqualTo("KRW");
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 게스트 수가 올바르게 설정된다")
		void 게스트수_설정() {
			// given
			int guestCount = 4;
			ReservationRequest.Create request = new ReservationRequest.Create(
				1L, LocalDate.of(2025, 1, 26), LocalDate.of(2025, 1, 28), guestCount);

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123");

			// then
			assertThat(reservation.getGuestCount()).isEqualTo(guestCount);
		}
	}

	private Reservation createPendingReservation() {
		ReservationRequest.Create request = createValidRequest();
		return Reservation.createPendingReservation(accommodation, guest, request, "ABC123");
	}

	private ReservationRequest.Create createValidRequest() {
		return new ReservationRequest.Create(
			1L,
			LocalDate.of(2025, 1, 26),
			LocalDate.of(2025, 1, 28),
			2
		);
	}
}
