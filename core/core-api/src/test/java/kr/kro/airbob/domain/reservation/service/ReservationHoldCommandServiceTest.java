package kr.kro.airbob.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.coupon.service.CouponUsageService;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationHoldReleaseNotAllowedException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationPaymentAttemptNotAllowedException;
import kr.kro.airbob.domain.reservation.exception.ReservationPaymentAttemptTooLateException;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.ReservationPaymentAttemptPolicy;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 보유 명령 서비스 테스트")
class ReservationHoldCommandServiceTest {

	private static final long MEMBER_ID = 7L;
	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

	@Mock private ReservationRepository reservationRepository;
	@Mock private ReservationHistoryRepository historyRepository;
	@Mock private CouponUsageService couponUsageService;
	@Mock private ReservationInventoryService inventoryService;

	private ReservationHoldCommandService service;

	@BeforeEach
	void setUp() {
		service = new ReservationHoldCommandService(
			reservationRepository,
			historyRepository,
			couponUsageService,
			new ReservationPaymentAttemptPolicy(Duration.ofSeconds(90)),
			inventoryService,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	@DisplayName("PAYMENT_PENDING 보유를 해제하면 만료·쿠폰 복원·이력을 한 번만 적용한다")
	void releasesPendingHoldOnce() {
		Reservation reservation = reservation(ReservationStatus.PAYMENT_PENDING, NOW.plusSeconds(900), true);
		givenLocked(reservation);

		var response = service.releaseHold(reservation.getReservationUid().toString(), MEMBER_ID);

		assertThat(response.status()).isEqualTo(ReservationStatus.EXPIRED);
		assertThat(response.releasedNow()).isTrue();
		assertThat(response.serverTime()).isEqualTo(NOW);
		then(inventoryService).should().releaseHeldIfOwned(
			reservation.getAccommodation().getId(),
			reservation.getCheckInDate(),
			reservation.getCheckOutDate(),
			reservation.getId());
		then(couponUsageService).should().restore(reservation.getId());
		then(historyRepository).should().save(any(ReservationHistory.class));
	}

	@Test
	@DisplayName("이미 EXPIRED인 보유 해제 재호출은 부수 효과 없이 성공한다")
	void replaysExpiredReleaseWithoutSideEffects() {
		Reservation reservation = reservation(ReservationStatus.EXPIRED, NOW.minusSeconds(1), true);
		givenLocked(reservation);

		var response = service.releaseHold(reservation.getReservationUid().toString(), MEMBER_ID);

		assertThat(response.releasedNow()).isFalse();
		then(inventoryService).shouldHaveNoInteractions();
		then(couponUsageService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("결제 대기나 만료가 아닌 예약은 R021로 거절한다")
	void rejectsReleaseFromOtherState() {
		Reservation reservation = reservation(ReservationStatus.CONFIRMED, NOW.plusSeconds(900), true);
		givenLocked(reservation);

		assertThatThrownBy(() -> service.releaseHold(reservation.getReservationUid().toString(), MEMBER_ID))
			.isInstanceOf(ReservationHoldReleaseNotAllowedException.class);
		then(couponUsageService).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("존재하지 않거나 다른 게스트의 예약은 동일한 R001로 응답한다")
	void hidesReservationOwnership() {
		UUID reservationUid = UUID.randomUUID();
		given(reservationRepository.findByReservationUidAndGuestIdWithLock(reservationUid, MEMBER_ID))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> service.releaseHold(reservationUid.toString(), MEMBER_ID))
			.isInstanceOf(ReservationNotFoundException.class);
	}

	@Test
	@DisplayName("남은 시간이 정확히 90초인 유료 예약은 토큰을 발급한다")
	void issuesAttemptAtMinimumBoundary() {
		Reservation reservation = reservation(
			ReservationStatus.PAYMENT_PENDING, NOW.plusSeconds(90), true);
		givenLocked(reservation);

		var response = service.beginPaymentAttempt(
			reservation.getReservationUid().toString(), MEMBER_ID);

		assertThat(response.paymentAttemptId()).isNotNull();
		assertThat(response.paymentAttemptId()).isEqualTo(reservation.getPaymentAttemptUid());
		assertThat(response.orderId()).isEqualTo(reservation.getReservationUid().toString());
		assertThat(response.amount()).isEqualTo(100_000L);
		assertThat(response.currency()).isEqualTo("KRW");
		assertThat(response.holdExpiresAt()).isEqualTo(NOW.plusSeconds(90));
		assertThat(response.remainingSeconds()).isEqualTo(90);
		assertThat(reservation.getExpiresAt()).isEqualTo(NOW.plusSeconds(90));
	}

	@Test
	@DisplayName("남은 시간이 90초보다 짧으면 최초 토큰 발급을 R022로 거절한다")
	void rejectsLateFirstAttempt() {
		Reservation reservation = reservation(
			ReservationStatus.PAYMENT_PENDING, NOW.plusSeconds(90).minusNanos(1), true);
		givenLocked(reservation);

		assertThatThrownBy(() -> service.beginPaymentAttempt(
			reservation.getReservationUid().toString(), MEMBER_ID))
			.isInstanceOf(ReservationPaymentAttemptTooLateException.class);
		assertThat(reservation.getPaymentAttemptUid()).isNull();
	}

	@Test
	@DisplayName("발급된 미소비 토큰은 만료 직전까지 동일 값으로 재응답한다")
	void replaysExistingUnconsumedAttempt() {
		Reservation reservation = reservation(
			ReservationStatus.PAYMENT_PENDING, NOW.plusNanos(1), true);
		UUID existingAttempt = UUID.randomUUID();
		reservation.issuePaymentAttempt(existingAttempt, NOW.minusSeconds(30));
		givenLocked(reservation);

		var response = service.beginPaymentAttempt(
			reservation.getReservationUid().toString(), MEMBER_ID);

		assertThat(response.paymentAttemptId()).isEqualTo(existingAttempt);
		assertThat(reservation.getPaymentAttemptStartedAt()).isEqualTo(NOW.minusSeconds(30));
	}

	@Test
	@DisplayName("소비된 토큰과 토큰 발급 대상이 아닌 예약은 새 결제 시도를 발급하지 않는다")
	void rejectsConsumedOrIneligibleAttempt() {
		Reservation consumed = reservation(
			ReservationStatus.PAYMENT_PENDING, NOW.plusSeconds(900), true);
		UUID attemptId = UUID.randomUUID();
		consumed.issuePaymentAttempt(attemptId, NOW.minusSeconds(5));
		consumed.consumePaymentAttempt(attemptId, NOW.minusSeconds(4));
		givenLocked(consumed);

		assertThatThrownBy(() -> service.beginPaymentAttempt(
			consumed.getReservationUid().toString(), MEMBER_ID))
			.isInstanceOf(ReservationPaymentAttemptNotAllowedException.class);

		Reservation ineligible = reservation(
			ReservationStatus.PAYMENT_PENDING, NOW.plusSeconds(900), false);
		givenLocked(ineligible);
		assertThatThrownBy(() -> service.beginPaymentAttempt(
			ineligible.getReservationUid().toString(), MEMBER_ID))
			.isInstanceOf(ReservationPaymentAttemptNotAllowedException.class);
	}

	private void givenLocked(Reservation reservation) {
		given(reservationRepository.findByReservationUidAndGuestIdWithLock(
			reservation.getReservationUid(), MEMBER_ID)).willReturn(Optional.of(reservation));
	}

	private Reservation reservation(
		ReservationStatus status,
		Instant expiresAt,
		boolean paymentAttemptRequired
	) {
		return Reservation.builder()
			.id(41L)
			.reservationUid(UUID.randomUUID())
			.accommodation(Accommodation.builder().id(17L).build())
			.guest(Member.builder().id(MEMBER_ID).build())
			.checkInDate(java.time.LocalDate.of(2026, 9, 1))
			.checkOutDate(java.time.LocalDate.of(2026, 9, 2))
			.totalPrice(100_000L)
			.currency("KRW")
			.status(status)
			.expiresAt(expiresAt)
			.paymentAttemptRequired(paymentAttemptRequired)
			.build();
	}
}
