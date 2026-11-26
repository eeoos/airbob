package kr.kro.airbob.core.domain.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import kr.kro.airbob.core.api.controller.v1.coupon.response.CouponResponse;
import kr.kro.airbob.core.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.core.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.core.support.cursor.dto.CouponCampaignCursorData;
import kr.kro.airbob.core.support.cursor.dto.CursorData;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import kr.kro.airbob.core.support.cursor.util.CursorDecoder;
import kr.kro.airbob.core.support.cursor.util.CursorEncoder;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;

@ExtendWith(MockitoExtension.class)
class CouponQueryServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 9, 30);

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private MemberCouponRepository memberCouponRepository;
	@Mock
	private CouponTimeProvider timeProvider;
	@Mock
	private kr.kro.airbob.core.domain.coupon.repository.CouponIssueUnitStore units;

	private CouponQueryService service;
	private CursorDecoder cursorDecoder;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		cursorDecoder = new CursorDecoder(objectMapper);
		service = new CouponQueryService(
			new CouponReader(couponRepository, memberCouponRepository, units),
			timeProvider,
			new CursorPageInfoCreator(new CursorEncoder(objectMapper)));
	}

	@Test
	@DisplayName("쿠폰 캠페인 상태는 발급과 동일한 MySQL 시각으로 계산한다")
	void findsCouponCampaignsWithIssuanceStatus() {
		CouponCampaignProjection upcoming = campaign(coupon(2L, NOW.plusDays(1), 100), false);
		CouponCampaignProjection open = campaign(coupon(1L, NOW.minusMinutes(30), 100), true);
		given(units.databaseNow()).willReturn(NOW);

		given(couponRepository.findCampaignsWithCursor(NOW, null, null, PageRequest.of(0, 20)))
			.willReturn(new SliceImpl<>(List.of(upcoming, open), PageRequest.of(0, 20), false));

		CouponResponse.CouponInfos response = CouponResponse.CouponInfos.from(service.getCouponCampaigns(
			new CursorRequest.CouponCampaignCursorPageRequest(20, null, null)));

		assertThat(response.infos())
			.extracting(CouponResponse.CouponInfo::id, CouponResponse.CouponInfo::issuanceStatus)
			.containsExactly(
				tuple(2L, CouponIssuanceStatus.UPCOMING),
				tuple(1L, CouponIssuanceStatus.OPEN));
		assertThat(response.pageInfo()).isEqualTo(new CursorResponse.PageInfo(false, null, 2));
		then(timeProvider).should(never()).now();
		then(units).should().databaseNow();

		then(memberCouponRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("회원이 발급받은 쿠폰을 최신 발급순과 보유 상태로 조회한다")
	void findsMyCouponsWithMemberCouponStatus() {
		MemberCouponProjection used = memberCoupon(coupon(2L, NOW.minusDays(2), 100), true);
		MemberCouponProjection available = memberCoupon(coupon(1L, NOW.minusDays(3), 100), false);
		given(timeProvider.now()).willReturn(NOW);
		given(memberCouponRepository.findByMemberIdWithCursor(10L, null, null, PageRequest.of(0, 20)))
			.willReturn(new SliceImpl<>(List.of(used, available), PageRequest.of(0, 20), false));

		CouponResponse.MemberCouponInfos response = CouponResponse.MemberCouponInfos.from(service.getMyCoupons(
			10L, new CursorRequest.CursorPageRequest(20, null, null)));

		assertThat(response.infos())
			.extracting(CouponResponse.MemberCouponInfo::couponId, CouponResponse.MemberCouponInfo::status)
			.containsExactly(
				tuple(2L, MemberCouponStatus.USED),
				tuple(1L, MemberCouponStatus.AVAILABLE));
		assertThat(response.pageInfo()).isEqualTo(new CursorResponse.PageInfo(false, null, 2));
		then(timeProvider).should().now();
		then(couponRepository).shouldHaveNoInteractions();
		then(units).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("캠페인 커서는 반환한 마지막 쿠폰의 발급 시작 시각을 사용한다")
	void createsCampaignCursorFromLastReturnedIssueStart() {
		LocalDateTime boundary = NOW.plusDays(2);
		LocalDateTime issueStartAt = NOW.plusHours(1).withNano(123456000);
		var first = campaign(coupon(15L, NOW.plusDays(1), 100), true);
		var last = campaign(coupon(7L, issueStartAt, 100), true);
		given(units.databaseNow()).willReturn(NOW);
		given(couponRepository.findCampaignsWithCursor(NOW, 19L, boundary, PageRequest.of(0, 2)))
			.willReturn(new SliceImpl<>(List.of(first, last), PageRequest.of(0, 2), true));

		var result = CouponResponse.CouponInfos
			.from(service.getCouponCampaigns(new CursorRequest.CouponCampaignCursorPageRequest(2, 19L, boundary)));

		assertThat(result.pageInfo().hasNext()).isTrue();
		assertThat(result.pageInfo().currentSize()).isEqualTo(2);
		assertThat(cursorDecoder.decode(result.pageInfo().nextCursor(), CouponCampaignCursorData.class))
			.isEqualTo(new CouponCampaignCursorData(7L, issueStartAt));
		then(couponRepository).should().findCampaignsWithCursor(NOW, 19L, boundary, PageRequest.of(0, 2));
	}

	@Test
	@DisplayName("내 쿠폰 커서는 쿠폰 ID가 아닌 보유 ID와 발급 시각을 사용한다")
	void createsMemberCursorFromOwnershipIdentity() {
		var first = memberCoupon(coupon(2L, NOW.minusDays(2), 100), false);
		var last = memberCoupon(coupon(1L, NOW.minusDays(3), 100), false);
		given(timeProvider.now()).willReturn(NOW);
		given(memberCouponRepository.findByMemberIdWithCursor(10L, 500L, NOW.plusHours(1), PageRequest.of(0, 2)))
			.willReturn(new SliceImpl<>(List.of(first, last), PageRequest.of(0, 2), true));

		var result = CouponResponse.MemberCouponInfos
			.from(service.getMyCoupons(10L, new CursorRequest.CursorPageRequest(2, 500L, NOW.plusHours(1))));

		assertThat(result.infos()).extracting(CouponResponse.MemberCouponInfo::couponId).containsExactly(2L, 1L);
		assertThat(result.pageInfo().hasNext()).isTrue();
		assertThat(result.pageInfo().currentSize()).isEqualTo(2);
		assertThat(cursorDecoder.decode(result.pageInfo().nextCursor(), CursorData.class))
			.isEqualTo(new CursorData(101L, NOW.withNano(123456000)));
		then(memberCouponRepository).should().findByMemberIdWithCursor(10L, 500L, NOW.plusHours(1),
			PageRequest.of(0, 2));
	}

	@Test
	@DisplayName("빈 쿠폰 목록은 다음 커서 없는 크기 0 페이지를 반환한다")
	void returnsEmptyPageInfo() {
		given(units.databaseNow()).willReturn(NOW);
		given(timeProvider.now()).willReturn(NOW);
		given(couponRepository.findCampaignsWithCursor(NOW, null, null, PageRequest.of(0, 20)))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));
		given(memberCouponRepository.findByMemberIdWithCursor(10L, null, null, PageRequest.of(0, 20)))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

		var campaigns = CouponResponse.CouponInfos
			.from(service.getCouponCampaigns(new CursorRequest.CouponCampaignCursorPageRequest(20, null, null)));
		var owned = CouponResponse.MemberCouponInfos
			.from(service.getMyCoupons(10L, new CursorRequest.CursorPageRequest(20, null, null)));

		assertThat(campaigns.infos()).isEmpty();
		assertThat(owned.infos()).isEmpty();
		assertThat(campaigns.pageInfo()).isEqualTo(new CursorResponse.PageInfo(false, null, 0));
		assertThat(owned.pageInfo()).isEqualTo(new CursorResponse.PageInfo(false, null, 0));
	}

	private Coupon coupon(Long id, LocalDateTime issueStartAt, int totalQuantity) {
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
			.build();
	}

	private MemberCouponProjection memberCoupon(Coupon coupon, boolean used) {
		return new MemberCouponProjection(coupon.getId() + 100, NOW.withNano(123456000),
			coupon.getId(), coupon.getName(), coupon.getDescription(),
			coupon.getDiscountType(), coupon.getDiscountValue(), coupon.getMinPaymentPrice(),
			coupon.getMaxDiscountAmount(), coupon.getUsableFrom(), coupon.getUsableUntil(), used, coupon.getIsActive());
	}

	private CouponCampaignProjection campaign(Coupon coupon, boolean hasRemainingStock) {
		return new CouponCampaignProjection(coupon.getId(), coupon.getName(), coupon.getDescription(),
			coupon.getDiscountType(), coupon.getDiscountValue(), coupon.getMinPaymentPrice(),
			coupon.getMaxDiscountAmount(), coupon.getIssueStartAt(), coupon.getIssueEndAt(),
			coupon.getUsableFrom(), coupon.getUsableUntil(), coupon.getTotalQuantity(), hasRemainingStock);
	}
}
