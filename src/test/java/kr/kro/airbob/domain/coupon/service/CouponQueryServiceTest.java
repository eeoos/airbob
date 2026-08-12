package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.MemberCouponStatus;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;

@ExtendWith(MockitoExtension.class)
class CouponQueryServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 9, 30);

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private MemberCouponRepository memberCouponRepository;
	@Mock
	private CouponTimeProvider timeProvider;

	private CouponQueryService service;

	@BeforeEach
	void setUp() {
		service = new CouponQueryService(couponRepository, memberCouponRepository, timeProvider);
		when(timeProvider.now()).thenReturn(NOW);
	}

	@Test
	@DisplayName("쿠폰 캠페인을 발급 시작 최신순과 현재 발급 상태로 조회한다")
	void findsCouponCampaignsWithIssuanceStatus() {
		Coupon upcoming = coupon(2L, NOW.plusDays(1), 100, 0);
		Coupon open = coupon(1L, NOW.minusMinutes(30), 100, 10);
		when(couponRepository.findCampaigns(NOW)).thenReturn(List.of(upcoming, open));

		CouponResponse.CouponInfos response = service.findCouponCampaigns();

		assertThat(response.infos())
			.extracting(CouponResponse.CouponInfo::id, CouponResponse.CouponInfo::issuanceStatus)
			.containsExactly(
				tuple(2L, CouponIssuanceStatus.UPCOMING),
				tuple(1L, CouponIssuanceStatus.OPEN));
	}

	@Test
	@DisplayName("회원이 발급받은 쿠폰을 최신 발급순과 보유 상태로 조회한다")
	void findsMyCouponsWithMemberCouponStatus() {
		MemberCoupon used = memberCoupon(12L, coupon(2L, NOW.minusDays(2), 100, 1), true);
		MemberCoupon available = memberCoupon(11L, coupon(1L, NOW.minusDays(3), 100, 1), false);
		when(memberCouponRepository.findByMemberIdOrderByCreatedAtDescIdDesc(10L))
			.thenReturn(List.of(used, available));

		CouponResponse.MemberCouponInfos response = service.findMyCoupons(10L);

		assertThat(response.infos())
			.extracting(CouponResponse.MemberCouponInfo::couponId, CouponResponse.MemberCouponInfo::status)
			.containsExactly(
				tuple(2L, MemberCouponStatus.USED),
				tuple(1L, MemberCouponStatus.AVAILABLE));
	}

	private Coupon coupon(Long id, LocalDateTime issueStartAt, int totalQuantity, int issuedQuantity) {
		return Coupon.builder()
			.id(id)
			.name("오전 10시 선착순 쿠폰")
			.discountType(DiscountType.FIXED_AMOUNT)
			.discountValue(10_000)
			.minPaymentPrice(50_000)
			.issueStartAt(issueStartAt)
			.issueEndAt(issueStartAt.plusHours(1))
			.usableFrom(issueStartAt)
			.usableUntil(issueStartAt.plusDays(30))
			.isActive(true)
			.totalQuantity(totalQuantity)
			.issuedQuantity(issuedQuantity)
			.build();
	}

	private MemberCoupon memberCoupon(Long id, Coupon coupon, boolean used) {
		return MemberCoupon.builder()
			.id(id)
			.coupon(coupon)
			.used(used)
			.build();
	}
}
