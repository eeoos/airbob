package kr.kro.airbob.domain.settlement.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.common.history.HistoryBase;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 정산 이력 — 전체 행 스냅샷(Transaction 성격, INSERT-only, 유효기간 없음).
// 직전 상태는 직전 이력 행으로 표현되며, change_type이 이번 변경의 성격을 나타낸다.
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementHistory extends HistoryBase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long settlementId;

	// --- 원본 비즈니스 컬럼 스냅샷 ---
	private Long hostId;
	private LocalDate settlementMonth;
	private Long grossAmount;
	private Long refundAmount;
	private Long netAmount;
	@Column(precision = 5, scale = 4)
	private BigDecimal commissionRate;
	private Long commissionAmount;
	private Long payoutAmount;
	@Enumerated(EnumType.STRING)
	private SettlementStatus status;

	// 사용자 요청에서 비롯된 변경: source_system/client_ip를 요청 컨텍스트에서 채움
	public static SettlementHistory of(Settlement settlement, ChangeType changeType, String changeReason) {
		return build(settlement, changeType, changeReason,
			UserContext.currentSourceSystem(), UserContext.currentClientIp());
	}

	// 시스템/배치에서 비롯된 변경: source_system을 명시 (client_ip 없음)
	public static SettlementHistory ofSystem(Settlement settlement, ChangeType changeType, String changeReason,
		String sourceSystem) {
		return build(settlement, changeType, changeReason, sourceSystem, null);
	}

	private static SettlementHistory build(Settlement s, ChangeType changeType, String changeReason,
		String sourceSystem, String clientIp) {
		return SettlementHistory.builder()
			.settlementId(s.getId())
			.hostId(s.getHostId())
			.settlementMonth(s.getSettlementMonth())
			.grossAmount(s.getGrossAmount())
			.refundAmount(s.getRefundAmount())
			.netAmount(s.getNetAmount())
			.commissionRate(s.getCommissionRate())
			.commissionAmount(s.getCommissionAmount())
			.payoutAmount(s.getPayoutAmount())
			.status(s.getStatus())
			.changeType(changeType)
			.changeReason(changeReason)
			.sourceSystem(sourceSystem)
			.clientIp(clientIp)
			.build();
	}
}
