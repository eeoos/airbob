package kr.kro.airbob.domain.settlement.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.domain.BaseEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 호스트 정산(현재상태 source of truth). (host_id, settlement_month) 유니크.
// payout = net - commission, commission = ROUND(net * commission_rate). 지급(PAID)되면 불변.
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long hostId;

	@Column(nullable = false)
	private LocalDate settlementMonth; // 월초

	@Column(nullable = false)
	private Long grossAmount;

	@Column(nullable = false)
	private Long refundAmount;

	@Column(nullable = false)
	private Long netAmount;

	@Column(nullable = false, precision = 5, scale = 4)
	private BigDecimal commissionRate;

	@Column(nullable = false)
	private Long commissionAmount;

	@Column(nullable = false)
	private Long payoutAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SettlementStatus status;

	private LocalDateTime settledAt;

	// net/rate로 commission·payout을 계산해 PENDING 정산을 생성
	public static Settlement createPending(Long hostId, LocalDate settlementMonth,
		long gross, long refund, BigDecimal commissionRate) {
		long net = gross - refund;
		long commission = calculateCommission(net, commissionRate);
		return Settlement.builder()
			.hostId(hostId)
			.settlementMonth(settlementMonth)
			.grossAmount(gross)
			.refundAmount(refund)
			.netAmount(net)
			.commissionRate(commissionRate)
			.commissionAmount(commission)
			.payoutAmount(net - commission)
			.status(SettlementStatus.PENDING)
			.build();
	}

	// PENDING 상태에서 재집계로 금액 갱신(율 스냅샷도 현재 율로 갱신)
	public void updateAmounts(long gross, long refund, BigDecimal commissionRate) {
		long net = gross - refund;
		long commission = calculateCommission(net, commissionRate);
		this.grossAmount = gross;
		this.refundAmount = refund;
		this.netAmount = net;
		this.commissionRate = commissionRate;
		this.commissionAmount = commission;
		this.payoutAmount = net - commission;
	}

	public void markPaid() {
		this.status = SettlementStatus.PAID;
		this.settledAt = LocalDateTime.now();
	}

	public boolean isPaid() {
		return this.status == SettlementStatus.PAID;
	}

	private static long calculateCommission(long net, BigDecimal rate) {
		return BigDecimal.valueOf(net)
			.multiply(rate)
			.setScale(0, RoundingMode.HALF_UP)
			.longValue();
	}
}
