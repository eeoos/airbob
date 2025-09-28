package kr.kro.airbob.kafka.consumer;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.payment.service.PaymentService;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentKafkaConsumer {

	private final ObjectMapper objectMapper;
	private final PaymentService paymentService;

	// 구독하는 토픽을 깔끔한 비즈니스 토픽으로 변경!
	@KafkaListener(topics = "reservation-pending", groupId = "payment-group")
	public void handleReservationPending(String payload) {
		try {
			ReservationEvent.ReservationPendingEvent event = objectMapper.readValue(payload, ReservationEvent.ReservationPendingEvent.class);

			paymentService.processPaymentConfirmation(event);

		} catch (JsonProcessingException e) {
			log.error("ReservationPendingEvent 파싱 실패: payload={}", payload, e);
			// TODO: DLQ 처리 로직
		}
	}

	// ReservationCancelled 이벤트 핸들러도 동일하게 'reservation-cancelled' 토픽을 구독하도록 수정...
}
