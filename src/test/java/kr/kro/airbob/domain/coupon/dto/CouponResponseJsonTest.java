package kr.kro.airbob.domain.coupon.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.repository.projection.MemberCouponProjection;

@JsonTest
@DisplayName("쿠폰 캠페인·보유 쿠폰 프론트 공유 JSON 계약")
class CouponResponseJsonTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 12, 0);

	@Autowired private ObjectMapper objectMapper;

	@Test
	@DisplayName("캠페인은 발급 상태와 발급·사용 기간을 구분하여 반환한다")
	void campaignContractIncludesAllIssuanceStatesAndSeparatePeriods() throws Exception {
		List<Coupon> campaigns = List.of(
			coupon(10, "발급 예정 쿠폰").issueStartAt(NOW.plusHours(1)).build(),
			coupon(11, "발급 중 쿠폰").build(),
			coupon(12, "매진 쿠폰").issuedQuantity(10).build());

		assertContract("coupon-campaigns.json", new CouponResponse.CouponInfos(campaigns.stream()
			.map(coupon -> CouponResponse.CouponInfo.of(coupon, NOW)).toList()));
	}

	@Test
	@DisplayName("보유 쿠폰은 매진·발급 종료와 무관하게 사용 상태를 계산하고 기간 경계를 지킨다")
	void memberContractSeparatesOwnershipFromCampaignAvailability() throws Exception {
		List<MemberCouponProjection> owned = List.of(
			owned(coupon(12, "매진 쿠폰").issuedQuantity(10).usableFrom(NOW).build(), false),
			owned(coupon(13, "사용 예정 쿠폰").usableFrom(NOW.plusHours(1)).build(), false),
			owned(coupon(14, "사용 완료 쿠폰").build(), true),
			owned(coupon(15, "만료 쿠폰").usableUntil(NOW).build(), false),
			owned(coupon(16, "비활성 쿠폰").isActive(false).build(), false),
			owned(coupon(17, "발급 종료 보유 쿠폰").issueEndAt(NOW).build(), false));

		assertContract("member-coupons.json", new CouponResponse.MemberCouponInfos(owned.stream()
			.map(memberCoupon -> CouponResponse.MemberCouponInfo.of(memberCoupon, NOW)).toList()));
	}

	private void assertContract(String name, Object response) throws Exception {
		try (var fixture = new ClassPathResource("contracts/" + name).getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(response)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
	}

	private Coupon.CouponBuilder<?, ?> coupon(long id, String name) {
		return Coupon.builder().id(id).name(name).discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(10_000).minPaymentPrice(50_000)
			.issueStartAt(NOW.minusDays(1)).issueEndAt(NOW.plusDays(1))
			.usableFrom(NOW.minusDays(1)).usableUntil(NOW.plusMonths(1))
			.isActive(true).totalQuantity(10).issuedQuantity(0);
	}

	private MemberCouponProjection owned(Coupon coupon, boolean used) {
		return new MemberCouponProjection(coupon.getId(), coupon.getName(), coupon.getDescription(),
			coupon.getDiscountType(), coupon.getDiscountValue(), coupon.getMinPaymentPrice(),
			coupon.getMaxDiscountAmount(), coupon.getUsableFrom(), coupon.getUsableUntil(), used, coupon.getIsActive());
	}
}
