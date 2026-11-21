package kr.kro.airbob.domain.settlement.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.settlement.entity.SettlementHistory;

public interface SettlementHistoryRepository extends JpaRepository<SettlementHistory, Long> {

	List<SettlementHistory> findBySettlementIdOrderByHistoryCreatedAtAsc(Long settlementId);
}
