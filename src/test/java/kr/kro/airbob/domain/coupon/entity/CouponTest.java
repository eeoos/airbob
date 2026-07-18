package kr.kro.airbob.domain.coupon.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.coupon.common.DiscountType;

class CouponTest {

	private static final LocalDateTime ISSUE_START = LocalDateTime.of(2026, 7, 18, 10, 0);
	private static final LocalDateTime ISSUE_END = ISSUE_START.plusMinutes(10);
	private static final LocalDateTime USABLE_FROM = ISSUE_START;
	private static final LocalDateTime USABLE_UNTIL = ISSUE_START.plusDays(30);

	private Coupon coupon(DiscountType type, int value, Integer minPayment, Integer maxDiscount) {
		return Coupon.builder()
			.name("테스트 쿠폰")
			.discountType(type)
			.discountValue(value)
			.minPaymentPrice(minPayment)
			.maxDiscountAmount(maxDiscount)
			.issueStartAt(ISSUE_START)
			.issueEndAt(ISSUE_END)
			.usableFrom(USABLE_FROM)
			.usableUntil(USABLE_UNTIL)
			.isActive(true)
			.totalQuantity(100)
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
	@DisplayName("발급 기간은 시작을 포함하고 종료를 포함하지 않는다")
	void issuePeriodUsesStartInclusiveEndExclusiveBoundary() {
		Coupon coupon = coupon(DiscountType.FIXED_AMOUNT, 5_000, null, null);

		assertThat(coupon.isIssueOpen(ISSUE_START.minusNanos(1))).isFalse();
		assertThat(coupon.isIssueOpen(ISSUE_START)).isTrue();
		assertThat(coupon.isIssueOpen(ISSUE_END.minusNanos(1))).isTrue();
		assertThat(coupon.isIssueOpen(ISSUE_END)).isFalse();
	}

	@Test
	@DisplayName("사용 기간은 시작을 포함하고 종료를 포함하지 않는다")
	void usagePeriodUsesStartInclusiveEndExclusiveBoundary() {
		Coupon coupon = coupon(DiscountType.FIXED_AMOUNT, 5_000, null, null);

		assertThat(coupon.isUsable(USABLE_FROM.minusNanos(1))).isFalse();
		assertThat(coupon.isUsable(USABLE_FROM)).isTrue();
		assertThat(coupon.isUsable(USABLE_UNTIL.minusNanos(1))).isTrue();
		assertThat(coupon.isUsable(USABLE_UNTIL)).isFalse();
	}

	@Test
	@DisplayName("발급 기간이 끝나도 사용 기간 안이면 쿠폰을 사용할 수 있다")
	void issuanceAndUsagePeriodsAreIndependent() {
		Coupon coupon = coupon(DiscountType.FIXED_AMOUNT, 5_000, null, null);
		LocalDateTime afterIssuance = ISSUE_END.plusDays(1);

		assertThat(coupon.isIssueOpen(afterIssuance)).isFalse();
		assertThat(coupon.isIssuable(afterIssuance)).isFalse();
		assertThat(coupon.isUsable(afterIssuance)).isTrue();
	}

	@Test
	@DisplayName("비활성 쿠폰은 기간 안이어도 발급하거나 사용할 수 없다")
	void inactiveCouponIsNeitherIssuableNorUsable() {
		Coupon inactive = Coupon.builder()
			.name("비활성 쿠폰")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(5_000)
			.issueStartAt(ISSUE_START)
			.issueEndAt(ISSUE_END)
			.usableFrom(USABLE_FROM)
			.usableUntil(USABLE_UNTIL)
			.isActive(false)
			.totalQuantity(100)
			.issuedQuantity(0)
			.build();

		assertThat(inactive.isIssuable(ISSUE_START)).isFalse();
		assertThat(inactive.isUsable(USABLE_FROM)).isFalse();
	}
}
