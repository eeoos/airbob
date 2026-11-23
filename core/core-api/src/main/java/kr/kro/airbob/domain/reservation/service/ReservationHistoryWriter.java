package kr.kro.airbob.domain.reservation.service;

import org.springframework.stereotype.Component;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationHistory;
import kr.kro.airbob.domain.reservation.repository.ReservationHistoryRepository;
import lombok.RequiredArgsConstructor;

/** 예약 이력 기록 구현 컴포넌트. 다른 도메인은 ReservationHistoryRepository 대신 이 컴포넌트로 기록한다. */
@Component
@RequiredArgsConstructor
public class ReservationHistoryWriter {

	private final ReservationHistoryRepository historyRepository;

	/** 사용자 요청에서 비롯된 변경을 기록한다. 출처 시스템과 클라이언트 IP는 요청 컨텍스트에서 채운다. */
	public void record(Reservation reservation, ChangeType changeType, String changeReason) {
		historyRepository.save(ReservationHistory.of(reservation, changeType, changeReason));
	}

	/** 시스템·컨슈머·배치에서 비롯된 변경을 호출한 쪽 트랜잭션 안에서 기록한다. 요청 클라이언트 IP는 남기지 않는다. */
	public void recordSystem(Reservation reservation, ChangeType changeType, String changeReason, String sourceSystem) {
		historyRepository.save(ReservationHistory.ofSystem(reservation, changeType, changeReason, sourceSystem));
	}
}
