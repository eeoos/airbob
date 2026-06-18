package kr.kro.airbob.domain.settlement.scheduler;

import java.time.YearMonth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.settlement.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 월 마감 정산 배치. 매월 1일 새벽 전월 정산을 생성(멱등).
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

	private final SettlementService settlementService;

	@Scheduled(cron = "0 0 4 1 * *")
	public void generatePreviousMonthSettlement() {
		YearMonth previousMonth = YearMonth.now().minusMonths(1);
		log.info("월 정산 배치 시작: month={}", previousMonth);
		settlementService.generateMonth(previousMonth);
		log.info("월 정산 배치 완료: month={}", previousMonth);
	}
}
