package kr.kro.airbob.domain.coupon.entity;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;

class CouponTest {

	private static final LocalDateTime ISSUE_START = LocalDateTime.of(2026, 7, 18, 10, 0);
	private static final LocalDateTime ISSUE_END = ISSUE_START.plusMinutes(10);
	private static final LocalDateTime USABLE_FROM = ISSUE_START;
	private static final LocalDateTime USABLE_UNTIL = ISSUE_START.plusDays(30);

	@Test
	void storesExplicitInputInstantsAsUtcAndComparesPreparedUpdatesInUtc() {
		var start = OffsetDateTime.parse("2027-01-01T00:30:00.123456+09:00").toInstant();
		var end = start.plusSeconds(600);
		var until = start.plusSeconds(86400);
		var coupon = Coupon.of(new CouponRequest.Create("UTC coupon", null, DiscountType.FIXED_AMOUNT,
			1000, null, null, start, end, start, until, true, 10));
		assertThat(coupon.getIssueStartAt()).isEqualTo("2026-12-31T15:30:00.123456");
		assertThat(coupon.getIssueEndAt().toInstant(ZoneOffset.UTC)).isEqualTo(end);
		assertThat(coupon.getUsableFrom().toInstant(ZoneOffset.UTC)).isEqualTo(start);
		assertThat(coupon.getUsableUntil().toInstant(ZoneOffset.UTC)).isEqualTo(until);
		var unchanged = new CouponRequest.Update(null, null, null, null, null, null,
			start, end, null, null, null, null);
		assertThat(coupon.changesPreparedIssuanceConfiguration(unchanged)).isFalse();
		var changed = new CouponRequest.Update(null, null, null, null, null, null,
			start.plusSeconds(60), null, null, until.plusSeconds(60), null, null);
		assertThat(coupon.changesPreparedIssuanceConfiguration(changed)).isTrue();
		coupon.updateWithDto(changed);
		assertThat(coupon.getIssueStartAt().toInstant(ZoneOffset.UTC)).isEqualTo(start.plusSeconds(60));
		assertThat(coupon.getIssueEndAt().toInstant(ZoneOffset.UTC)).isEqualTo(end);
		assertThat(coupon.getUsableUntil().toInstant(ZoneOffset.UTC)).isEqualTo(until.plusSeconds(60));
	}

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
		assertThat(coupon.isUsable(afterIssuance)).isTrue();
	}

	@Test
	@DisplayName("비활성 쿠폰은 기간 안이어도 사용할 수 없다")
	void inactiveCouponIsNotUsable() {
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
			.build();

		assertThat(inactive.isUsable(USABLE_FROM)).isFalse();
	}

	@Test
	@DisplayName("발급 시작 전 쿠폰은 UPCOMING 상태다")
	void upcomingIssuanceStatus() {
		Coupon coupon = coupon(DiscountType.FIXED_AMOUNT, 5_000, null, null);

		assertThat(CouponIssuanceStatus.resolve(coupon.getIssueStartAt(), coupon.getTotalQuantity(), false, ISSUE_START.minusNanos(1)))
			.isEqualTo(CouponIssuanceStatus.UPCOMING);
	}

	@Test
	@DisplayName("발급 기간 안에 재고가 남은 쿠폰은 OPEN 상태다")
	void openIssuanceStatus() {
		Coupon coupon = coupon(DiscountType.FIXED_AMOUNT, 5_000, null, null);

		assertThat(CouponIssuanceStatus.resolve(coupon.getIssueStartAt(), coupon.getTotalQuantity(), true, ISSUE_START))
			.isEqualTo(CouponIssuanceStatus.OPEN);
	}

	@Test
	@DisplayName("발급 기간 안에 재고가 소진된 쿠폰은 SOLD_OUT 상태다")
	void soldOutIssuanceStatus() {
		Coupon coupon = Coupon.builder()
			.name("매진 쿠폰")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(5_000)
			.issueStartAt(ISSUE_START)
			.issueEndAt(ISSUE_END)
			.usableFrom(USABLE_FROM)
			.usableUntil(USABLE_UNTIL)
			.isActive(true)
			.totalQuantity(100)
			.build();

		assertThat(CouponIssuanceStatus.resolve(coupon.getIssueStartAt(), coupon.getTotalQuantity(), false, ISSUE_START))
			.isEqualTo(CouponIssuanceStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("과거 수량 미설정 쿠폰은 재고 행이 없어도 기존 OPEN 조회 상태를 유지한다")
	void preservesLegacyNullableQuantityStatus() {
		assertThat(CouponIssuanceStatus.resolve(ISSUE_START, null, false, ISSUE_START))
			.isEqualTo(CouponIssuanceStatus.OPEN);
	}
}
