package kr.kro.airbob.kafka.consumer;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.service.ReservationHoldService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.outbox.DebeziumEventParser;
import kr.kro.airbob.outbox.EventEnvelope;
import kr.kro.airbob.outbox.EventType;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 이벤트 소비자 테스트")
class ReservationEventsConsumerTest {

	private static final Long ACCOMMODATION_ID = 42L;
	private static final LocalDate CHECK_IN_DATE = LocalDate.of(2026, 8, 20);
	private static final LocalDate CHECK_OUT_DATE = LocalDate.of(2026, 8, 23);

	@Mock private ReservationService reservationService;
	@Mock private ReservationHoldService reservationHoldService;
	@Mock private DebeziumEventParser debeziumEventParser;
	@Mock private Acknowledgment acknowledgment;

	@Test
	@DisplayName("예약 확정 완료 이벤트는 해당 숙소와 숙박 기간의 Redis 홀드를 제거한다")
	void confirmedReservationRemovesHold() {
		String message = "reservation-confirmed";
		ReservationEvent.ReservationConfirmedEvent payload =
			new ReservationEvent.ReservationConfirmedEvent(
				ACCOMMODATION_ID, CHECK_IN_DATE, CHECK_OUT_DATE);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.RESERVATION_CONFIRMED.name());
		given(debeziumEventParser.parse(message, ReservationEvent.ReservationConfirmedEvent.class))
			.willReturn(EventEnvelope.of(EventType.RESERVATION_CONFIRMED, payload, Instant.EPOCH));

		consumer().handleReservationEvents(message, acknowledgment);

		then(reservationHoldService).should()
			.removeHold(ACCOMMODATION_ID, CHECK_IN_DATE, CHECK_OUT_DATE);
		then(acknowledgment).should().acknowledge();
	}

	@Test
	@DisplayName("예약 만료 완료 이벤트는 해당 숙소와 숙박 기간의 Redis 홀드를 제거한다")
	void expiredReservationRemovesHold() {
		String message = "reservation-expired";
		ReservationEvent.ReservationExpiredEvent payload =
			new ReservationEvent.ReservationExpiredEvent(
				ACCOMMODATION_ID, CHECK_IN_DATE, CHECK_OUT_DATE);
		given(debeziumEventParser.getEventType(message))
			.willReturn(EventType.RESERVATION_EXPIRED.name());
		given(debeziumEventParser.parse(message, ReservationEvent.ReservationExpiredEvent.class))
			.willReturn(EventEnvelope.of(EventType.RESERVATION_EXPIRED, payload, Instant.EPOCH));

		consumer().handleReservationEvents(message, acknowledgment);

		then(reservationHoldService).should()
			.removeHold(ACCOMMODATION_ID, CHECK_IN_DATE, CHECK_OUT_DATE);
		then(acknowledgment).should().acknowledge();
	}

	private ReservationEventsConsumer consumer() {
		return new ReservationEventsConsumer(
			reservationService, reservationHoldService, debeziumEventParser);
	}
}
