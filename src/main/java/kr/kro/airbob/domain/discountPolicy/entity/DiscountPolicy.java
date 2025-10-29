package kr.kro.airbob.domain.discountPolicy.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.common.domain.UpdatableEntity;
import kr.kro.airbob.domain.discountPolicy.common.DiscountType;
import kr.kro.airbob.domain.discountPolicy.common.PromotionType;
import kr.kro.airbob.domain.discountPolicy.dto.DiscountPolicyRequest;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiscountPolicy extends UpdatableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private Double discountRate;

	private String description;

	@Enumerated(EnumType.STRING)
	private DiscountType discountType;

	@Enumerated(EnumType.STRING)
	private PromotionType promotionType;

	private Integer minPaymentPrice;

	private Integer maxApplyPrice;

	@Column(nullable = false)
	private LocalDateTime startDate;

	@Column(nullable = false)
	private LocalDateTime endDate;

	@Column(nullable = false)
	private Boolean isActive;

	public static DiscountPolicy of(DiscountPolicyRequest.Create discountPolicyCreateDto) {
        return DiscountPolicy.builder()
                .name(discountPolicyCreateDto.name())
                .discountRate(discountPolicyCreateDto.discountRate())
                .description(discountPolicyCreateDto.description())
                .discountType(discountPolicyCreateDto.discountType())
                .promotionType(discountPolicyCreateDto.promotionType())
                .minPaymentPrice(discountPolicyCreateDto.minPaymentPrice())
                .maxApplyPrice(discountPolicyCreateDto.maxApplyPrice())
                .startDate(discountPolicyCreateDto.startDate())
                .endDate(discountPolicyCreateDto.endDate())
                .isActive(discountPolicyCreateDto.isActive()).build();
	}

	public void updateWithDto(DiscountPolicyRequest.Update dto) {
		if (dto.name() != null) this.name = dto.name();
		if (dto.discountRate() != null) this.discountRate = dto.discountRate();
		if (dto.description() != null) this.description = dto.description();
		if (dto.discountType() != null) this.discountType = dto.discountType();
		if (dto.promotionType() != null) this.promotionType = dto.promotionType();
		if (dto.minPaymentPrice() != null) this.minPaymentPrice = dto.minPaymentPrice();
		if (dto.maxApplyPrice() != null) this.maxApplyPrice = dto.maxApplyPrice();
		if (dto.startDate() != null) this.startDate = dto.startDate();
		if (dto.endDate() != null) this.endDate = dto.endDate();
		if (dto.isActive() != null) this.isActive = dto.isActive();
	}

	public void deactivate() {
		this.isActive = false;
	}
	public void activate() {
		this.isActive = true;
	}
}
