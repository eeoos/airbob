package kr.kro.airbob.core.domain.settlement;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import kr.kro.airbob.core.support.persistence.history.ChangeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

	private static final String GENERATE_LOCK_PREFIX = "LOCK:SETTLEMENT:GENERATE:";
	private static final long LOCK_WAIT_SECONDS = 5;

	private final SettlementReader settlementReader;
	private final SettlementGenerator settlementGenerator;
	private final SettlementHistoryWriter historyWriter;
	private final RedissonClient redissonClient;
	private final PlatformTransactionManager transactionManager;
	private final Clock clock;

	// 한 달 정산 생성/재집계. 멀티 인스턴스 스케줄러/수동 동시 실행 시 UNIQUE(host_id, month) 충돌을
	// 막기 위해 월 단위 분산 락으로 직렬화. 락은 트랜잭션 바깥에서 감싸 커밋까지 보호
	public void generateMonth(YearMonth month) {
		RLock lock = redissonClient.getLock(GENERATE_LOCK_PREFIX + month);
		boolean acquired = false;
		try {
			acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS); // leaseTime 없음 = watchdog 자동 갱신
			if (!acquired) {
				log.warn("정산 생성 락 획득 실패(다른 노드 처리 중 추정) → skip: month={}", month);
				return;
			}
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> generateMonthInTx(month));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("정산 생성 락 대기 중 인터럽트: month={}", month);
		} finally {
			if (acquired) {
				releaseGenerateLock(lock, month);
			}
		}
	}

	private void releaseGenerateLock(RLock lock, YearMonth month) {
		try {
			// unlock이 소유자를 직접 검증하므로 별도 Redis 조회 없이 watchdog 정리까지 시도한다.
			lock.unlock();
		} catch (RuntimeException exception) {
			// 락 해제 실패가 이미 끝난 집계 결과나 원래의 집계 예외를 덮어쓰지 않게 격리한다.
			log.warn("정산 생성 락 해제 실패: month={}", month, exception);
		}
	}

	// 실제 집계/적재 (락 보유 상태에서 TransactionTemplate으로 트랜잭션 실행)
	private void generateMonthInTx(YearMonth month) {
		int hosts = settlementGenerator.generate(month);
		log.info("정산 월 집계 완료: month={}, hosts={}", month, hosts);
	}

	// [from, to] 월 구간 백필 (월마다 독립 락 + 트랜잭션)
	public void backfill(YearMonth from, YearMonth to) {
		for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
			generateMonth(month);
		}
	}

	// 정산 지급 처리: PENDING -> PAID (이미 PAID거나 아직 마감 안 된 달이면 거부)
	@Transactional
	public void markPaid(Long settlementId) {
		Settlement settlement = settlementReader.readForUpdate(settlementId);
		if (settlement.isPaid()) {
			throw new SettlementAlreadyPaidException();
		}
		if (!isMonthClosed(settlement.getSettlementMonth())) {
			throw new SettlementMonthNotClosedException();
		}
		settlement.markPaid(clock.instant());
		historyWriter.record(settlement, ChangeType.STATUS_CHANGE, "정산 지급 완료");
	}

	// 관리자: 월별 정산 조회(상태 필터 optional)
	@Transactional(readOnly = true)
	public List<SettlementSummary> getSettlements(YearMonth month, SettlementStatus status) {
		return settlementReader.readMonth(month.atDay(1), status).stream()
			.map(SettlementSummary::from)
			.toList();
	}

	// settlement_month(월초)가 속한 달이 이미 끝났는지(= 현재 월보다 이전)
	private boolean isMonthClosed(LocalDate settlementMonth) {
		return YearMonth.from(settlementMonth).isBefore(YearMonth.now(clock));
	}

	// 정산 상세(숙소별 내역). 호스트 본인 정산만 조회 가능.
	@Transactional(readOnly = true)
	public HostSettlementDetail getSettlementDetail(Long settlementId, Long requestingHostId) {
		Settlement settlement = settlementReader.read(settlementId);
		if (!settlement.getHostId().equals(requestingHostId)) {
			throw new SettlementAccessDeniedException();
		}
		YearMonth month = YearMonth.from(settlement.getSettlementMonth());
		return new HostSettlementDetail(
			SettlementSummary.from(settlement),
			settlementReader.readLines(settlement.getHostId(), month));
	}

	// 호스트 대시보드 요약(예정/누적 지급 총액·건수). settlement이 이미 (host,month) 사전집계라 on-the-fly 집계.
	@Transactional(readOnly = true)
	public HostSettlementTotals getHostSummary(Long hostId) {
		return settlementReader.readHostTotals(hostId);
	}

	@Transactional(readOnly = true)
	public List<SettlementSummary> getHostSettlements(Long hostId, LocalDate from, LocalDate to) {
		return settlementReader.readHostMonths(hostId, from, to).stream()
			.map(SettlementSummary::from)
			.toList();
	}
}
