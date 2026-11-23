package kr.kro.airbob.core.domain.settlement;

import org.springframework.stereotype.Component;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.core.domain.settlement.repository.SettlementHistoryRepository;
import lombok.RequiredArgsConstructor;

/** 정산 이력 기록 구현 컴포넌트. 호출한 쪽 트랜잭션 안에서 돈다. */
@Component
@RequiredArgsConstructor
public class SettlementHistoryWriter {

	private final SettlementHistoryRepository historyRepository;

	public void record(Settlement settlement, ChangeType changeType, String changeReason) {
		historyRepository.save(SettlementHistory.of(settlement, changeType, changeReason));
	}

	public void recordSystem(Settlement settlement, ChangeType changeType, String changeReason, String source) {
		historyRepository.save(SettlementHistory.ofSystem(settlement, changeType, changeReason, source));
	}
}
