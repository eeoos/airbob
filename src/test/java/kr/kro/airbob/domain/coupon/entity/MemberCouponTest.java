package kr.kro.airbob.domain.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.MemberCouponStatus;

class MemberCouponTest {

	private static final LocalDateTime USABLE_FROM = LocalDateTime.of(2026, 8, 20, 10, 0);
	private static final LocalDateTime USABLE_UNTIL = USABLE_FROM.plusDays(30);

	@Test
	@DisplayName("사용 시작 전 보유 쿠폰은 UPCOMING 상태다")
	void upcomingBeforeUsablePeriod() {
		assertThat(memberCoupon(false).status(USABLE_FROM.minusNanos(1)))
			.isEqualTo(MemberCouponStatus.UPCOMING);
	}

	@Test
	@DisplayName("사용 기간 안의 미사용 쿠폰은 AVAILABLE 상태다")
	void availableDuringUsablePeriod() {
		assertThat(memberCoupon(false).status(USABLE_FROM))
			.isEqualTo(MemberCouponStatus.AVAILABLE);
	}

	@Test
	@DisplayName("사용 기한이 지난 미사용 쿠폰은 EXPIRED 상태다")
	void expiredAfterUsablePeriod() {
		assertThat(memberCoupon(false).status(USABLE_UNTIL))
			.isEqualTo(MemberCouponStatus.EXPIRED);
	}

	@Test
	@DisplayName("사용한 쿠폰은 사용 기간과 관계없이 USED 상태다")
	void usedTakesPrecedenceOverPeriod() {
		assertThat(memberCoupon(true).status(USABLE_UNTIL))
			.isEqualTo(MemberCouponStatus.USED);
	}

	@Test
	@DisplayName("비활성화된 미사용 쿠폰은 사용 기간이어도 UNAVAILABLE 상태다")
	void inactiveCouponIsUnavailable() {
		assertThat(memberCoupon(false, false).status(USABLE_FROM))
			.isEqualTo(MemberCouponStatus.UNAVAILABLE);
	}

	private MemberCoupon memberCoupon(boolean used) {
		return memberCoupon(used, true);
	}

	private MemberCoupon memberCoupon(boolean used, boolean active) {
		Coupon coupon = Coupon.builder()
			.name("보유 쿠폰")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(10_000)
			.issueStartAt(USABLE_FROM.minusHours(1))
			.issueEndAt(USABLE_FROM)
			.usableFrom(USABLE_FROM)
			.usableUntil(USABLE_UNTIL)
			.isActive(active)
			.totalQuantity(100)
			.issuedQuantity(1)
			.build();
		return MemberCoupon.builder()
			.coupon(coupon)
			.used(used)
			.build();
	}
}
