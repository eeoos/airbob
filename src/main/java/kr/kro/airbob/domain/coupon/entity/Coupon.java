package kr.kro.airbob.domain.coupon.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.CouponIssueStrategy;
import kr.kro.airbob.domain.coupon.common.CouponTime;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import kr.kro.airbob.domain.coupon.exception.CouponInvalidConfigurationException;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DiscountType discountType;

	// PERCENTAGE 면 할인율(%),
	// FIXED_AMOUNT 면 할인 금액(원)
	@Column(nullable = false)
	private Integer discountValue;

	// 최소 결제 금액(미만이면 적용 불가)
	private Integer minPaymentPrice;

	// 정률 할인 시 최대 할인 금액 상한
	private Integer maxDiscountAmount;

	// Business timestamps are stored and compared as UTC; the API exposes UTC instants and the frontend formats them.
	@Column(nullable = false)
	private LocalDateTime issueStartAt;

	@Column(nullable = false)
	private LocalDateTime issueEndAt;

	@Column(nullable = false)
	private LocalDateTime usableFrom;

	@Column(nullable = false)
	private LocalDateTime usableUntil;

	@Column(nullable = false)
	private Boolean isActive;

	// 신규 생성은 양수만 허용한다. 과거 NULL 데이터의 이관 전까지 DB nullable은 유지한다.
	private Integer totalQuantity;

	// 조건부 UPDATE 비교군 전용. SKIP LOCKED 경로는 갱신하거나 재고 판단에 사용하지 않는다.
	@Column(nullable = false)
	private Integer issuedQuantity;

	private LocalDateTime stockPreparedAt;

	@Builder.Default
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 32)
	private CouponIssueStrategy issueStrategy = CouponIssueStrategy.SKIP_SURROGATE_RR;

	public static Coupon of(CouponRequest.Create dto) {
		validateQuantity(dto.totalQuantity());
		return Coupon.builder()
			.name(dto.name())
			.description(dto.description())
			.discountType(dto.discountType())
			.discountValue(dto.discountValue())
			.minPaymentPrice(dto.minPaymentPrice())
			.maxDiscountAmount(dto.maxDiscountAmount())
			.issueStartAt(CouponTime.toUtc(dto.issueStartAt()))
			.issueEndAt(CouponTime.toUtc(dto.issueEndAt()))
			.usableFrom(CouponTime.toUtc(dto.usableFrom()))
			.usableUntil(CouponTime.toUtc(dto.usableUntil()))
			.isActive(dto.isActive())
			.totalQuantity(dto.totalQuantity())
			.issuedQuantity(0)
			.build();
	}

	public void updateWithDto(CouponRequest.Update dto) {
		if (dto.name() != null) this.name = dto.name();
		if (dto.description() != null) this.description = dto.description();
		if (dto.discountType() != null) this.discountType = dto.discountType();
		if (dto.discountValue() != null) this.discountValue = dto.discountValue();
		if (dto.minPaymentPrice() != null) this.minPaymentPrice = dto.minPaymentPrice();
		if (dto.maxDiscountAmount() != null) this.maxDiscountAmount = dto.maxDiscountAmount();
		if (dto.issueStartAt() != null) this.issueStartAt = CouponTime.toUtc(dto.issueStartAt());
		if (dto.issueEndAt() != null) this.issueEndAt = CouponTime.toUtc(dto.issueEndAt());
		if (dto.usableFrom() != null) this.usableFrom = CouponTime.toUtc(dto.usableFrom());
		if (dto.usableUntil() != null) this.usableUntil = CouponTime.toUtc(dto.usableUntil());
		if (dto.isActive() != null) this.isActive = dto.isActive();
		if (dto.totalQuantity() != null) this.totalQuantity = dto.totalQuantity();
	}

	public void deactivate() {
		this.isActive = false;
	}

	public void activate() {
		this.isActive = true;
	}

	public boolean changesPreparedIssuanceConfiguration(CouponRequest.Update dto) {
		return differs(CouponTime.toUtc(dto.issueStartAt()), issueStartAt)
			|| differs(CouponTime.toUtc(dto.issueEndAt()), issueEndAt)
			|| differs(dto.isActive(), isActive)
			|| differs(dto.totalQuantity(), totalQuantity);
	}

	public void markStockPrepared(LocalDateTime preparedAt) {
		if (preparedAt == null) {
			throw new IllegalArgumentException("재고 준비 시각은 필수입니다.");
		}
		if (stockPreparedAt != null) {
			throw new IllegalStateException("재고 준비 이력은 덮어쓸 수 없습니다.");
		}
		this.stockPreparedAt = preparedAt;
	}

	public boolean isStockPrepared() {
		return stockPreparedAt != null;
	}

	public void changeTotalQuantity(Integer quantity) {
		validateQuantity(quantity);
		this.totalQuantity = quantity;
	}

	public static void validateQuantity(Integer quantity) {
		if (quantity == null || quantity < 1) {
			throw new CouponInvalidConfigurationException();
		}
	}

	public void validateConfiguration() {
		validateQuantity(totalQuantity);
		if (name == null || name.isBlank() || discountType == null || discountValue == null
			|| discountValue <= 0 || (discountType == DiscountType.PERCENTAGE && discountValue > 100)
			|| (minPaymentPrice != null && minPaymentPrice < 0)
			|| (maxDiscountAmount != null && maxDiscountAmount < 0)
			|| isActive == null || issueStartAt == null || issueEndAt == null
			|| !issueEndAt.isAfter(issueStartAt) || usableFrom == null || usableUntil == null
			|| !usableUntil.isAfter(usableFrom) || usableUntil.isBefore(issueEndAt)) {
			throw new CouponInvalidConfigurationException();
		}
	}

	public void scheduleIssuance(LocalDateTime start, LocalDateTime end) {
		if (start == null || end == null || !end.isAfter(start)) {
			throw new IllegalArgumentException("유효한 발급 기간이 필요합니다.");
		}
		this.issueStartAt = start;
		this.issueEndAt = end;
	}

	// 발급 한도 소진 여부 (totalQuantity 가 null 이면 무제한)
	public boolean isSoldOut() {
		return totalQuantity != null && issuedQuantity >= totalQuantity;
	}

	public boolean isIssueOpen(LocalDateTime now) {
		return !now.isBefore(issueStartAt) && now.isBefore(issueEndAt);
	}

	public boolean isIssuable(LocalDateTime now) {
		return Boolean.TRUE.equals(isActive) && isIssueOpen(now) && !isSoldOut();
	}

	public CouponIssuanceStatus issuanceStatus(LocalDateTime now) {
		return CouponIssuanceStatus.resolve(issueStartAt, totalQuantity, issuedQuantity, now);
	}

	// 발급된 쿠폰을 사용할 수 있는지 (활성·기간만 확인, 재고는 무관)
	public boolean isUsable(LocalDateTime now) {
		return Boolean.TRUE.equals(isActive)
			&& !now.isBefore(usableFrom)
			&& now.isBefore(usableUntil);
	}

	/**
	 * 결제(원가) 금액에 대한 할인액을 계산한다.
	 *  - 최소 결제 금액 미달 시 0
	 *  - PERCENTAGE: 비율 적용 후 maxDiscountAmount 상한
	 *  - FIXED_AMOUNT: 정액
	 * 할인액이 원가를 넘지 않도록 보정한다.
	 */
	public long calculateDiscount(long amount) {
		if (minPaymentPrice != null && amount < minPaymentPrice) {
			return 0L;
		}

		long discount;
		if (discountType == DiscountType.PERCENTAGE) {
			discount = amount * discountValue / 100;
			if (maxDiscountAmount != null && discount > maxDiscountAmount) {
				discount = maxDiscountAmount;
			}
		} else {
			discount = discountValue;
		}
		return Math.min(discount, amount);
	}

	// 발급 가능 재고 (무제한이면 null)
	public Integer remainingQuantity() {
		return totalQuantity == null ? null : Math.max(0, totalQuantity - issuedQuantity);
	}

	private boolean differs(Object requested, Object current) {
		return requested != null && !requested.equals(current);
	}
}
