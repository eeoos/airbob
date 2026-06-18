package kr.kro.airbob.domain.settlement.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.domain.settlement.dto.HostMonthlyAggregate;
import kr.kro.airbob.domain.settlement.dto.SettlementResponse;
import kr.kro.airbob.domain.settlement.entity.Settlement;
import kr.kro.airbob.domain.settlement.entity.SettlementHistory;
import kr.kro.airbob.domain.settlement.exception.SettlementAlreadyPaidException;
import kr.kro.airbob.domain.settlement.exception.SettlementNotFoundException;
import kr.kro.airbob.domain.settlement.repository.SettlementHistoryRepository;
import kr.kro.airbob.domain.settlement.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

	private static final String SOURCE_BATCH = "BATCH";

	private final SettlementRepository settlementRepository;
	private final SettlementHistoryRepository settlementHistoryRepository;

	@Value("${settlement.commission-rate:0.03}")
	private BigDecimal commissionRate;

	// 한 달 정산 생성/재집계. 호스트별 원장 집계 → PENDING upsert(이미 PAID면 불변). 멱등.
	@Transactional
	public void generateMonth(YearMonth month) {
		LocalDate monthStart = month.atDay(1);
		LocalDate monthEnd = month.atEndOfMonth();

		List<HostMonthlyAggregate> aggregates = settlementRepository.aggregateByHostForMonth(monthStart, monthEnd);
		for (HostMonthlyAggregate aggregate : aggregates) {
			upsertPending(aggregate, monthStart);
		}
		log.info("정산 월 집계 완료: month={}, hosts={}", month, aggregates.size());
	}

	// [from, to] 월 구간 백필
	@Transactional
	public void backfill(YearMonth from, YearMonth to) {
		for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
			generateMonth(month);
		}
	}

	// 정산 지급 처리: PENDING → PAID (이미 PAID면 거부)
	@Transactional
	public void markPaid(Long settlementId) {
		Settlement settlement = settlementRepository.findById(settlementId)
			.orElseThrow(SettlementNotFoundException::new);
		if (settlement.isPaid()) {
			throw new SettlementAlreadyPaidException();
		}
		settlement.markPaid();
		settlementHistoryRepository.save(
			SettlementHistory.of(settlement, ChangeType.STATUS_CHANGE, "정산 지급 완료"));
	}

	@Transactional(readOnly = true)
	public List<SettlementResponse.HostSettlement> getHostSettlements(Long hostId, LocalDate from, LocalDate to) {
		return settlementRepository
			.findByHostIdAndSettlementMonthBetweenOrderBySettlementMonthAsc(hostId, from, to)
			.stream()
			.map(SettlementResponse.HostSettlement::from)
			.toList();
	}

	private void upsertPending(HostMonthlyAggregate aggregate, LocalDate monthStart) {
		long gross = nz(aggregate.getGrossAmount());
		long refund = nz(aggregate.getRefundAmount());

		Optional<Settlement> existing =
			settlementRepository.findByHostIdAndSettlementMonth(aggregate.getHostId(), monthStart);

		if (existing.isPresent()) {
			Settlement settlement = existing.get();
			if (settlement.isPaid()) {
				return; // 지급 완료 정산은 재집계로 건드리지 않음(불변)
			}
			settlement.updateAmounts(gross, refund, commissionRate);
			settlementHistoryRepository.save(
				SettlementHistory.ofSystem(settlement, ChangeType.UPDATE, "월 정산 재집계", SOURCE_BATCH));
		} else {
			Settlement settlement = settlementRepository.save(
				Settlement.createPending(aggregate.getHostId(), monthStart, gross, refund, commissionRate));
			settlementHistoryRepository.save(
				SettlementHistory.ofSystem(settlement, ChangeType.CREATE, "월 정산 생성", SOURCE_BATCH));
		}
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}
}
