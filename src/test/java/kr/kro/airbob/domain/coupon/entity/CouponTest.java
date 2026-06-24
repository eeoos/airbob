package kr.kro.airbob.domain.coupon.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.coupon.common.DiscountType;

class CouponTest {

	private Coupon coupon(DiscountType type, int value, Integer minPayment, Integer maxDiscount) {
		return Coupon.builder()
			.name("테스트 쿠폰")
			.discountType(type)
			.discountValue(value)
			.minPaymentPrice(minPayment)
			.maxDiscountAmount(maxDiscount)
			.startDate(LocalDateTime.now().minusDays(1))
			.endDate(LocalDateTime.now().plusDays(1))
			.isActive(true)
			.issuedQuantity(0)
			.build();
	}

	@Test
	@DisplayName("정률 할인은 비율만큼 할인한다")
	void percentageDiscount() {
		Coupon coupon = coupon(DiscountType.PERCENTAGE, 10, null, null);
		assertThat(coupon.calculateDiscount(100_000L)).isEqualTo(10_000L);
	}

	@Test
	@DisplayName("정률 할인은 최대 할인 금액 상한을 넘지 않는다")
	void percentageDiscountCappedByMax() {
		Coupon coupon = coupon(DiscountType.PERCENTAGE, 50, null, 30_000);
		assertThat(coupon.calculateDiscount(100_000L)).isEqualTo(30_000L);
	}

	@Test
	@DisplayName("정액 할인은 고정 금액을 할인한다")
	void fixedAmountDiscount() {
		Coupon coupon = coupon(DiscountType.FIXED_AMOUNT, 5_000, null, null);
		assertThat(coupon.calculateDiscount(100_000L)).isEqualTo(5_000L);
	}

	@Test
	@DisplayName("최소 결제 금액 미달이면 할인하지 않는다")
	void belowMinPaymentNoDiscount() {
		Coupon coupon = coupon(DiscountType.FIXED_AMOUNT, 5_000, 50_000, null);
		assertThat(coupon.calculateDiscount(30_000L)).isZero();
	}

	@Test
	@DisplayName("할인액은 결제 금액을 초과하지 않는다")
	void discountNotExceedingAmount() {
		Coupon coupon = coupon(DiscountType.FIXED_AMOUNT, 20_000, null, null);
		assertThat(coupon.calculateDiscount(10_000L)).isEqualTo(10_000L);
	}

	@Test
	@DisplayName("비활성/만료 쿠폰은 사용할 수 없다")
	void usability() {
		Coupon active = coupon(DiscountType.FIXED_AMOUNT, 5_000, null, null);
		assertThat(active.isUsable(LocalDateTime.now())).isTrue();

		Coupon expired = Coupon.builder()
			.name("만료 쿠폰").discountType(DiscountType.FIXED_AMOUNT).discountValue(5_000)
			.startDate(LocalDateTime.now().minusDays(10))
			.endDate(LocalDateTime.now().minusDays(1))
			.isActive(true).issuedQuantity(0).build();
		assertThat(expired.isUsable(LocalDateTime.now())).isFalse();
	}
}
