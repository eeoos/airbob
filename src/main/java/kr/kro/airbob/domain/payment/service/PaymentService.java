package kr.kro.airbob.domain.payment.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final ApplicationEventPublisher eventPublisher;

	@Async
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handleReservationPendingEvent(ReservationEvent.ReservationPendingEvent event) {
		log.info("[결제]: 예약 ID {} 결제 시작(총액: {})", event.reservationId(), event.totalPrice());

		/*
		PG 연동 로직
		 */
		try {
			// 외부 API 호출을 시뮬레이션하기 위해 잠시 대기
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		boolean paymentSuccess = true; // 가짜 결제 성공

		if (paymentSuccess) {
			log.info("[결제]: 예약 ID {} 결제 성공", event.reservationId());
			eventPublisher.publishEvent(new PaymentEvent.PaymentSucceededEvent(event.reservationId()));
		}else{
			// 결제 실패 이벤트
		}
	}
}
