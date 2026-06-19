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
import kr.kro.airbob.domain.settlement.dto.SettlementStatusSum;
import kr.kro.airbob.domain.settlement.entity.Settlement;
import kr.kro.airbob.domain.settlement.entity.SettlementHistory;
import kr.kro.airbob.domain.settlement.entity.SettlementStatus;
import kr.kro.airbob.domain.settlement.exception.SettlementAccessDeniedException;
import kr.kro.airbob.domain.settlement.exception.SettlementAlreadyPaidException;
import kr.kro.airbob.domain.settlement.exception.SettlementMonthNotClosedException;
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

	// 정산 지급 처리: PENDING → PAID (이미 PAID거나 아직 마감 안 된 달이면 거부)
	@Transactional
	public void markPaid(Long settlementId) {
		Settlement settlement = settlementRepository.findById(settlementId)
			.orElseThrow(SettlementNotFoundException::new);
		if (settlement.isPaid()) {
			throw new SettlementAlreadyPaidException();
		}
		if (!isMonthClosed(settlement.getSettlementMonth())) {
			throw new SettlementMonthNotClosedException();
		}
		settlement.markPaid();
		settlementHistoryRepository.save(
			SettlementHistory.of(settlement, ChangeType.STATUS_CHANGE, "정산 지급 완료"));
	}

	// 관리자: 월별 정산 조회(상태 필터 optional)
	@Transactional(readOnly = true)
	public List<SettlementResponse.AdminSettlement> getSettlements(YearMonth month, SettlementStatus status) {
		LocalDate monthStart = month.atDay(1);
		List<Settlement> settlements = (status == null)
			? settlementRepository.findBySettlementMonthOrderByHostIdAsc(monthStart)
			: settlementRepository.findBySettlementMonthAndStatusOrderByHostIdAsc(monthStart, status);
		return settlements.stream()
			.map(SettlementResponse.AdminSettlement::from)
			.toList();
	}

	// settlement_month(월초)가 속한 달이 이미 끝났는지(= 현재 월보다 이전)
	private static boolean isMonthClosed(LocalDate settlementMonth) {
		return YearMonth.from(settlementMonth).isBefore(YearMonth.now());
	}

	// 정산 상세(숙소별 내역). 호스트 본인 정산만 조회 가능.
	@Transactional(readOnly = true)
	public SettlementResponse.SettlementDetail getSettlementDetail(Long settlementId, Long requestingHostId) {
		Settlement settlement = settlementRepository.findById(settlementId)
			.orElseThrow(SettlementNotFoundException::new);
		if (!settlement.getHostId().equals(requestingHostId)) {
			throw new SettlementAccessDeniedException();
		}
		YearMonth month = YearMonth.from(settlement.getSettlementMonth());
		List<SettlementResponse.LineItem> items = settlementRepository
			.findLineItems(settlement.getHostId(), month.atDay(1), month.atEndOfMonth())
			.stream()
			.map(SettlementResponse.LineItem::from)
			.toList();
		return SettlementResponse.SettlementDetail.of(settlement, items);
	}

	// 호스트 대시보드 요약(예정/누적 지급 총액·건수). settlement이 이미 (host,month) 사전집계라 on-the-fly 집계.
	@Transactional(readOnly = true)
	public SettlementResponse.HostSummary getHostSummary(Long hostId) {
		long pendingPayout = 0;
		long pendingCount = 0;
		long paidPayout = 0;
		long paidCount = 0;

		for (SettlementStatusSum row : settlementRepository.aggregateByHostGroupedByStatus(hostId)) {
			SettlementStatus status = SettlementStatus.valueOf(row.getStatus());
			long payout = row.getPayoutSum() == null ? 0L : row.getPayoutSum();
			long count = row.getCnt() == null ? 0L : row.getCnt();
			if (status == SettlementStatus.PENDING) {
				pendingPayout = payout;
				pendingCount = count;
			} else if (status == SettlementStatus.PAID) {
				paidPayout = payout;
				paidCount = count;
			}
		}
		return new SettlementResponse.HostSummary(pendingPayout, pendingCount, paidPayout, paidCount);
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
