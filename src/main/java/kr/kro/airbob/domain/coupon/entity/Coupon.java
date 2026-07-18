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
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 쿠폰 정의(캠페인). 할인 규칙과 발급 한도를 보유하며, 발급된 인스턴스는 {@code MemberCoupon} 이 표현한다.
 */
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

	// PERCENTAGE 면 할인율(%), FIXED_AMOUNT 면 할인 금액(원)
	@Column(nullable = false)
	private Integer discountValue;

	// 최소 결제 금액(미만이면 적용 불가)
	private Integer minPaymentPrice;

	// 정률 할인 시 최대 할인 금액 상한
	private Integer maxDiscountAmount;

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

	// 발급 한도 (null = 무제한)
	private Integer totalQuantity;

	// 현재 발급 수 (락/무락 발급 방식의 정합성 기준)
	@Column(nullable = false)
	private Integer issuedQuantity;

	public static Coupon of(CouponRequest.Create dto) {
		return Coupon.builder()
			.name(dto.name())
			.description(dto.description())
			.discountType(dto.discountType())
			.discountValue(dto.discountValue())
			.minPaymentPrice(dto.minPaymentPrice())
			.maxDiscountAmount(dto.maxDiscountAmount())
			.issueStartAt(dto.issueStartAt())
			.issueEndAt(dto.issueEndAt())
			.usableFrom(dto.usableFrom())
			.usableUntil(dto.usableUntil())
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
		if (dto.issueStartAt() != null) this.issueStartAt = dto.issueStartAt();
		if (dto.issueEndAt() != null) this.issueEndAt = dto.issueEndAt();
		if (dto.usableFrom() != null) this.usableFrom = dto.usableFrom();
		if (dto.usableUntil() != null) this.usableUntil = dto.usableUntil();
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
		return differs(dto.issueStartAt(), issueStartAt)
			|| differs(dto.issueEndAt(), issueEndAt)
			|| differs(dto.isActive(), isActive)
			|| differs(dto.totalQuantity(), totalQuantity);
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
