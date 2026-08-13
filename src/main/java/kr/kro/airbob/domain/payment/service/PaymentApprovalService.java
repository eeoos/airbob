package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalService {

	private final ReservationRepository reservationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final OutboxEventPublisher outboxEventPublisher;
	private final Clock clock;

	@Transactional
	public boolean preparePgCall(PaymentRequest.Confirm request) {
		UUID reservationUid = parseReservationUid(request.orderId());
		Reservation reservation = reservationRepository.findByReservationUidWithLock(reservationUid)
			.orElseThrow(ReservationNotFoundException::new);
		validateConfirmRequest(request, reservation);

		if (!reservation.startPayment(clock.instant())) {
			log.info("[결제 승인 선점-SKIP] PG 호출을 시작할 수 없는 예약입니다. UID: {}, status: {}",
				reservationUid, reservation.getStatus());
			return false;
		}

		historyRepository.save(ReservationHistory.ofSystem(
			reservation, ChangeType.STATUS_CHANGE, "결제 승인 처리 시작", "KAFKA"));
		outboxEventPublisher.save(EventType.PG_CALL_REQUESTED, request);
		log.info("[결제 승인 선점] 예약 상태 PAYMENT_PROCESSING 변경 및 PG 호출 이벤트 발행. UID: {}",
			reservationUid);
		return true;
	}

	private void validateConfirmRequest(PaymentRequest.Confirm request, Reservation reservation) {
		if (!reservation.matchesPaymentRequest(request.orderId(), request.amount().longValue())) {
			throw new InvalidInputException("결제 승인 요청이 예약 정보와 일치하지 않습니다.");
		}
	}

	private UUID parseReservationUid(String orderId) {
		try {
			return UUID.fromString(orderId);
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException("결제 승인 주문 번호가 UUID 형식이 아닙니다.");
		}
	}
}
