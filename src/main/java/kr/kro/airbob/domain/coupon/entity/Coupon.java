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
	private LocalDateTime startDate;

	@Column(nullable = false)
	private LocalDateTime endDate;

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
			.startDate(dto.startDate())
			.endDate(dto.endDate())
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
		if (dto.startDate() != null) this.startDate = dto.startDate();
		if (dto.endDate() != null) this.endDate = dto.endDate();
		if (dto.isActive() != null) this.isActive = dto.isActive();
		if (dto.totalQuantity() != null) this.totalQuantity = dto.totalQuantity();
	}

	public void deactivate() {
		this.isActive = false;
	}

	public void activate() {
		this.isActive = true;
	}
}
