package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.support.cursor.dto.CouponCampaignCursorData;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.coupon.CouponCampaignPage;
import kr.kro.airbob.domain.coupon.CouponCampaignProjection;
import kr.kro.airbob.domain.coupon.MemberCouponPage;
import kr.kro.airbob.domain.coupon.MemberCouponProjection;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponQueryService {

	private final CouponReader couponReader;
	private final CouponTimeProvider timeProvider;
	private final CursorPageInfoCreator cursorPageInfoCreator;

	@Transactional(readOnly = true)
	public CouponCampaignPage findCouponCampaigns(CursorRequest.CouponCampaignCursorPageRequest request) {
		LocalDateTime now = couponReader.databaseNow();
		Slice<CouponCampaignProjection> slice = couponReader.readCampaigns(now, request);
		List<CouponCampaignPage.Item> campaigns = slice.getContent().stream()
			.map(coupon -> CouponCampaignPage.Item.of(coupon, now))
			.toList();
		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			slice.getContent(), slice.hasNext(),
			coupon -> new CouponCampaignCursorData(coupon.id(), coupon.issueStartAt()));
		return new CouponCampaignPage(campaigns, pageInfo);
	}

	@Transactional(readOnly = true)
	public MemberCouponPage findMyCoupons(Long memberId, CursorRequest.CursorPageRequest request) {
		LocalDateTime now = timeProvider.now();
		Slice<MemberCouponProjection> slice = couponReader.readMemberCoupons(memberId, request);
		List<MemberCouponPage.Item> coupons = slice.getContent().stream()
			.map(memberCoupon -> MemberCouponPage.Item.of(memberCoupon, now))
			.toList();
		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			slice.getContent(), slice.hasNext(), MemberCouponProjection::memberCouponId,
			MemberCouponProjection::createdAt);
		return new MemberCouponPage(coupons, pageInfo);
	}
}
