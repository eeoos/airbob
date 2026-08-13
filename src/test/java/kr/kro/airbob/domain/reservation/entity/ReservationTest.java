package kr.kro.airbob.domain.reservation.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationDateException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationLocalTimeException;
import kr.kro.airbob.domain.reservation.exception.InvalidReservationStatusException;

@DisplayName("Reservation 엔티티 테스트")
class ReservationTest {
	private static final String TIME_ZONE_ID = "Asia/Seoul";
	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private Accommodation accommodation;
	private Member guest;

	@Test
	@DisplayName("리뷰 가능 상태는 CONFIRMED와 CANCELLATION_FAILED뿐이다")
	void reviewableReservationStatuses() {
		assertThat(ReservationStatus.CONFIRMED.isReviewableReservation()).isTrue();
		assertThat(ReservationStatus.CANCELLATION_FAILED.isReviewableReservation()).isTrue();
		assertThat(ReservationStatus.CANCELLATION_PENDING.isReviewableReservation()).isFalse();
		assertThat(ReservationStatus.CANCELLED.isReviewableReservation()).isFalse();
		assertThat(ReservationStatus.PAYMENT_PENDING.isReviewableReservation()).isFalse();
		assertThat(ReservationStatus.EXPIRED.isReviewableReservation()).isFalse();
	}

	@Test
	@DisplayName("확정 이후 파생된 취소 상태는 중복 결제 완료 이벤트에서 이미 확정된 예약으로 본다")
	void confirmedPaymentStatuses() {
		assertThat(ReservationStatus.CONFIRMED.hasConfirmedPayment()).isTrue();
		assertThat(ReservationStatus.CANCELLATION_PENDING.hasConfirmedPayment()).isTrue();
		assertThat(ReservationStatus.CANCELLATION_FAILED.hasConfirmedPayment()).isTrue();
		assertThat(ReservationStatus.CANCELLED.hasConfirmedPayment()).isTrue();
		assertThat(ReservationStatus.PAYMENT_PENDING.hasConfirmedPayment()).isFalse();
		assertThat(ReservationStatus.EXPIRED.hasConfirmedPayment()).isFalse();
	}

	@BeforeEach
	void setUp() {
		accommodation = Accommodation.builder()
			.id(1L)
			.basePrice(100_000L)
			.checkInTime(LocalTime.of(15, 0))
			.checkOutTime(LocalTime.of(11, 0))
			.timeZoneId(TIME_ZONE_ID)
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
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);

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
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);

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
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);

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
			assertThatThrownBy(() -> Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW))
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
			assertThatThrownBy(() -> Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW))
				.isInstanceOf(InvalidReservationDateException.class);
		}
	}

	@Nested
	@DisplayName("상태 전이 테스트")
	class StatusTransitionTest {

		@Test
		@DisplayName("미만료 PAYMENT_PENDING 예약은 결제 처리를 선점한다")
		void startsPaymentBeforeExpiry() {
			Reservation reservation = createPendingReservation();

			boolean started = reservation.startPayment(NOW);

			assertThat(started).isTrue();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		}

		@Test
		@DisplayName("만료 시각과 같거나 지난 예약은 결제 처리를 선점하지 않는다")
		void doesNotStartPaymentAtOrAfterExpiry() {
			Reservation reservation = createPendingReservation();

			boolean started = reservation.startPayment(reservation.getExpiresAt());

			assertThat(started).isFalse();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		}

		@Test
		@DisplayName("이미 결제 처리 중이면 중복 요청을 선점하지 않는다")
		void doesNotStartPaymentTwice() {
			Reservation reservation = createPendingReservation();
			assertThat(reservation.startPayment(NOW)).isTrue();

			boolean startedAgain = reservation.startPayment(NOW.plusSeconds(1));

			assertThat(startedAgain).isFalse();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		}

		@Test
		@DisplayName("PAYMENT_PROCESSING 상태에서 confirm() 호출 시 CONFIRMED로 변경된다")
		void 정상_결제처리중에서_확정() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);

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
		@DisplayName("PAYMENT_PROCESSING 상태도 PG 실패나 보상 시 EXPIRED로 변경된다")
		void expiresPaymentProcessingReservation() {
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);

			reservation.expire();

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
		}

		@Test
		@DisplayName("CONFIRMED 상태에서 취소 요청 시 CANCELLATION_PENDING으로 변경된다")
		void 정상_확정에서_취소요청() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
			reservation.confirm();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

			// when
			reservation.requestCancellation();

			// then
			assertThat(reservation.getStatus().name()).isEqualTo("CANCELLATION_PENDING");
		}

		@Test
		@DisplayName("CANCELLATION_PENDING 상태에서 failCancellation() 호출 시 CANCELLATION_FAILED로 변경된다")
		void 정상_취소대기에서_취소실패() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
			reservation.confirm();
			reservation.requestCancellation();
			assertThat(reservation.getStatus().name()).isEqualTo("CANCELLATION_PENDING");

			// when
			reservation.failCancellation();

			// then
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
		}

		@Test
		@DisplayName("CANCELLATION_PENDING 상태에서 completeCancellation() 호출 시 CANCELLED로 변경된다")
		void 정상_취소대기에서_취소완료() {
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
			reservation.confirm();
			reservation.requestCancellation();

			reservation.completeCancellation();

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		}

		@Test
		@DisplayName("CANCELLATION_FAILED 예약은 새 attempt 식별자 없이 재요청할 수 없다")
		void 취소실패에서_재요청_거부() {
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
			reservation.confirm();
			reservation.requestCancellation();
			reservation.failCancellation();

			assertThatThrownBy(reservation::requestCancellation)
				.isInstanceOf(InvalidReservationStatusException.class);
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
		}

		@Test
		@DisplayName("CANCELLATION_FAILED 예약에 늦은 성공 이벤트가 오면 CANCELLED로 수렴한다")
		void 취소실패에서_늦은성공_수렴() {
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
			reservation.confirm();
			reservation.requestCancellation();
			reservation.failCancellation();

			assertThat(reservation.completeCancellation()).isTrue();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		}

		@Test
		@DisplayName("CONFIRMED 상태에서 expire() 호출 시 InvalidReservationStatusException이 발생한다")
		void 예외_확정상태에서_만료시도() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
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
			assertThatThrownBy(reservation::requestCancellation)
				.isInstanceOf(InvalidReservationStatusException.class)
				.extracting(e -> ((InvalidReservationStatusException)e).getErrorCode())
				.isEqualTo(ErrorCode.CANNOT_CANCEL_RESERVATION);
		}

		@Test
		@DisplayName("CANCELLED 상태에서 confirm() 호출 시 InvalidReservationStatusException이 발생한다")
		void 예외_취소상태에서_확정시도() {
			// given
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
			reservation.confirm();
			reservation.requestCancellation();

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
			reservation.startPayment(NOW);
			reservation.confirm();
			reservation.requestCancellation();
			reservation.failCancellation();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);

			// when - 같은 메서드 재호출
			reservation.failCancellation();

			// then - 상태 유지 (예외 없음)
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
		}

		@Test
		@DisplayName("레거시 CANCELLED 예약은 PG 취소 실패 복구 시 CANCELLATION_FAILED로 되돌린다")
		void 레거시_취소실패_복구() {
			Reservation reservation = createPendingReservation();
			reservation.startPayment(NOW);
			reservation.confirm();
			reservation.requestCancellation();
			reservation.completeCancellation();

			reservation.recoverLegacyCancellationFailure();

			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLATION_FAILED);
		}

		@Test
		@DisplayName("PAYMENT_PENDING 상태에서 failCancellation() 호출 시 상태 오류가 발생한다")
		void PAYMENT_PENDING에서_failCancellation_호출시_오류() {
			// given
			Reservation reservation = createPendingReservation();
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);

			assertThatThrownBy(reservation::failCancellation)
				.isInstanceOf(InvalidReservationStatusException.class);
			assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		}
	}

	@Nested
	@DisplayName("팩토리 메서드 테스트")
	class FactoryMethodTest {

		@Test
		@DisplayName("DST 전환으로 존재하지 않는 숙소 현지 체크인 시각은 예약을 거절한다")
		void DST_gap_현지시각은_예약_거절() {
			Accommodation newYorkAccommodation = Accommodation.builder()
				.id(1L)
				.basePrice(100_000L)
				.checkInTime(LocalTime.of(2, 30))
				.checkOutTime(LocalTime.of(1, 30))
				.timeZoneId("America/New_York")
				.build();
			ReservationRequest.Create request = new ReservationRequest.Create(
				1L,
				LocalDate.of(2026, 3, 8),
				LocalDate.of(2026, 3, 9),
				2
			);

			assertThatThrownBy(() -> Reservation.createPendingReservation(
				newYorkAccommodation, guest, request, "ABC123", NOW))
				.isInstanceOf(InvalidReservationLocalTimeException.class)
				.extracting(e -> ((InvalidReservationLocalTimeException)e).getErrorCode())
				.isEqualTo(ErrorCode.RESERVATION_LOCAL_TIME_INVALID);
		}

		@Test
		@DisplayName("DST 종료로 두 번 존재하는 숙소 현지 시각은 첫 번째 시점으로 고정한다")
		void DST_overlap_현지시각은_첫번째_시점_사용() {
			Accommodation newYorkAccommodation = Accommodation.builder()
				.id(1L)
				.basePrice(100_000L)
				.checkInTime(LocalTime.of(1, 30))
				.checkOutTime(LocalTime.of(0, 30))
				.timeZoneId("America/New_York")
				.build();
			ReservationRequest.Create request = new ReservationRequest.Create(
				1L,
				LocalDate.of(2026, 11, 1),
				LocalDate.of(2026, 11, 2),
				2
			);

			Reservation reservation = Reservation.createPendingReservation(
				newYorkAccommodation, guest, request, "ABC123", NOW);

			assertThat(reservation.getCheckInAt()).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
		}

		@Test
		@DisplayName("뉴욕 DST 경계를 지나도 숙소 현지 시각을 정확한 UTC 시점으로 저장한다")
		void 뉴욕_DST_시간대_스냅샷과_UTC_시점_계산() {
			Accommodation newYorkAccommodation = Accommodation.builder()
				.id(1L)
				.basePrice(100_000L)
				.checkInTime(LocalTime.of(15, 0))
				.checkOutTime(LocalTime.of(11, 0))
				.timeZoneId("America/New_York")
				.build();
			ReservationRequest.Create request = new ReservationRequest.Create(
				1L,
				LocalDate.of(2026, 3, 7),
				LocalDate.of(2026, 3, 9),
				2
			);
			Instant now = Instant.parse("2026-01-01T00:00:00Z");

			Reservation reservation = Reservation.createPendingReservation(
				newYorkAccommodation, guest, request, "ABC123", now);

			assertThat(reservation.getCheckInDate()).isEqualTo(LocalDate.of(2026, 3, 7));
			assertThat(reservation.getCheckOutDate()).isEqualTo(LocalDate.of(2026, 3, 9));
			assertThat(reservation.getCheckInAt()).isEqualTo(Instant.parse("2026-03-07T20:00:00Z"));
			assertThat(reservation.getCheckOutAt()).isEqualTo(Instant.parse("2026-03-09T15:00:00Z"));
			assertThat(reservation.getTimeZoneId()).isEqualTo("America/New_York");
			assertThat(reservation.getExpiresAt()).isEqualTo(Instant.parse("2026-01-01T00:15:00Z"));
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 PAYMENT_PENDING 상태로 생성된다")
		void 정상_예약_생성_상태확인() {
			// given
			ReservationRequest.Create request = createValidRequest();

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);

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
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);

			// then
			Instant expectedCheckIn = Instant.parse("2025-01-26T06:00:00Z");
			Instant expectedCheckOut = Instant.parse("2025-01-28T02:00:00Z");

			assertThat(reservation.getCheckInAt()).isEqualTo(expectedCheckIn);
			assertThat(reservation.getCheckOutAt()).isEqualTo(expectedCheckOut);
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 만료시간이 15분 후로 설정된다")
		void 만료시간_설정() {
			// given
			ReservationRequest.Create request = createValidRequest();
			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);

			// then
			assertThat(reservation.getExpiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 예약코드가 설정된다")
		void 예약코드_설정() {
			// given
			ReservationRequest.Create request = createValidRequest();
			String reservationCode = "XYZ789";

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, reservationCode, NOW);

			// then
			assertThat(reservation.getReservationCode()).isEqualTo(reservationCode);
		}

		@Test
		@DisplayName("createPendingReservation 호출 시 통화가 KRW로 설정된다")
		void 통화_설정() {
			// given
			ReservationRequest.Create request = createValidRequest();

			// when
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);

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
			Reservation reservation = Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);

			// then
			assertThat(reservation.getGuestCount()).isEqualTo(guestCount);
		}
	}

	private Reservation createPendingReservation() {
		ReservationRequest.Create request = createValidRequest();
		return Reservation.createPendingReservation(accommodation, guest, request, "ABC123", NOW);
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
