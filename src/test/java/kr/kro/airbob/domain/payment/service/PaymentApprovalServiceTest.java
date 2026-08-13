package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제 승인 선점 서비스 테스트")
class PaymentApprovalServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

	@Mock private ReservationRepository reservationRepository;
	@Mock private ReservationHistoryRepository historyRepository;
	@Mock private OutboxEventPublisher outboxEventPublisher;

	private PaymentApprovalService paymentApprovalService;

	@BeforeEach
	void setUp() {
		paymentApprovalService = new PaymentApprovalService(
			reservationRepository,
			historyRepository,
			outboxEventPublisher,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	@DisplayName("미만료 예약을 잠근 뒤 결제 처리 상태와 PG 호출 이벤트를 원자적으로 만든다")
	void claimsPendingReservationAndPublishesPgCall() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = reservation(reservationUid, ReservationStatus.PAYMENT_PENDING, NOW.plusSeconds(1));
		PaymentRequest.Confirm request = request(reservationUid, 100_000);
		given(reservationRepository.findByReservationUidWithLock(reservationUid))
			.willReturn(Optional.of(reservation));
		ArgumentCaptor<ReservationHistory> historyCaptor = ArgumentCaptor.forClass(ReservationHistory.class);

		boolean claimed = paymentApprovalService.preparePgCall(request);

		assertThat(claimed).isTrue();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		then(historyRepository).should().save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.PAYMENT_PROCESSING);
		assertThat(historyCaptor.getValue().getChangeType()).isEqualTo(ChangeType.STATUS_CHANGE);
		then(outboxEventPublisher).should().save(EventType.PG_CALL_REQUESTED, request);
	}

	@Test
	@DisplayName("만료 시각과 정확히 같으면 PG 호출 이벤트를 만들지 않는다")
	void rejectsAtExactExpiry() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = reservation(reservationUid, ReservationStatus.PAYMENT_PENDING, NOW);
		PaymentRequest.Confirm request = request(reservationUid, 100_000);
		given(reservationRepository.findByReservationUidWithLock(reservationUid))
			.willReturn(Optional.of(reservation));

		boolean claimed = paymentApprovalService.preparePgCall(request);

		assertThat(claimed).isFalse();
		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxEventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("이미 결제 처리 중이면 중복 PG 호출 이벤트를 만들지 않는다")
	void skipsDuplicateClaim() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = reservation(
			reservationUid, ReservationStatus.PAYMENT_PROCESSING, NOW.plusSeconds(1));
		PaymentRequest.Confirm request = request(reservationUid, 100_000);
		given(reservationRepository.findByReservationUidWithLock(reservationUid))
			.willReturn(Optional.of(reservation));

		boolean claimed = paymentApprovalService.preparePgCall(request);

		assertThat(claimed).isFalse();
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxEventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("예약 금액과 다른 요청은 상태를 바꾸거나 PG 호출 이벤트를 만들지 않는다")
	void rejectsMismatchedAmount() {
		UUID reservationUid = UUID.randomUUID();
		Reservation reservation = reservation(reservationUid, ReservationStatus.PAYMENT_PENDING, NOW.plusSeconds(1));
		PaymentRequest.Confirm request = request(reservationUid, 90_000);
		given(reservationRepository.findByReservationUidWithLock(reservationUid))
			.willReturn(Optional.of(reservation));

		assertThatThrownBy(() -> paymentApprovalService.preparePgCall(request))
			.isInstanceOf(InvalidInputException.class)
			.hasMessageContaining("결제 승인 요청");

		assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxEventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("주문 번호가 UUID 형식이 아니면 저장소를 조회하지 않는다")
	void rejectsMalformedOrderId() {
		PaymentRequest.Confirm request = new PaymentRequest.Confirm(
			"payment-key", "not-a-uuid", 100_000);

		assertThatThrownBy(() -> paymentApprovalService.preparePgCall(request))
			.isInstanceOf(InvalidInputException.class)
			.hasMessageContaining("주문 번호");

		then(reservationRepository).shouldHaveNoInteractions();
		then(historyRepository).shouldHaveNoInteractions();
		then(outboxEventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("주문 번호에 해당하는 예약이 없으면 영구 오류로 종료한다")
	void rejectsMissingReservation() {
		UUID reservationUid = UUID.randomUUID();
		PaymentRequest.Confirm request = request(reservationUid, 100_000);
		given(reservationRepository.findByReservationUidWithLock(reservationUid))
			.willReturn(Optional.empty());

		assertThatThrownBy(() -> paymentApprovalService.preparePgCall(request))
			.isInstanceOf(ReservationNotFoundException.class);

		then(historyRepository).shouldHaveNoInteractions();
		then(outboxEventPublisher).shouldHaveNoInteractions();
	}

	private PaymentRequest.Confirm request(UUID reservationUid, int amount) {
		return new PaymentRequest.Confirm("payment-key", reservationUid.toString(), amount);
	}

	private Reservation reservation(UUID reservationUid, ReservationStatus status, Instant expiresAt) {
		return Reservation.builder()
			.id(1L)
			.reservationUid(reservationUid)
			.totalPrice(100_000L)
			.status(status)
			.expiresAt(expiresAt)
			.build();
	}
}
