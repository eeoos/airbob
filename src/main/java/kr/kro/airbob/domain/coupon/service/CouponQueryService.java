package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CouponCampaignCursorData;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection;
import kr.kro.airbob.domain.coupon.repository.projection.MemberCouponProjection;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponQueryService {

	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final CouponTimeProvider timeProvider;
	private final CouponIssueUnitStore units;
	private final CursorPageInfoCreator cursorPageInfoCreator;

	@Transactional(readOnly = true)
	public CouponResponse.CouponInfos findCouponCampaigns(CursorRequest.CouponCampaignCursorPageRequest request) {
		LocalDateTime now = units.databaseNow();
		Slice<CouponCampaignProjection> slice = couponRepository.findCampaignsWithCursor(
			now, request.lastId(), request.lastIssueStartAt(), PageRequest.of(0, request.size()));
		List<CouponResponse.CouponInfo> infos = slice.getContent().stream()
			.map(coupon -> CouponResponse.CouponInfo.of(coupon, now))
			.toList();
		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			slice.getContent(), slice.hasNext(),
			coupon -> new CouponCampaignCursorData(coupon.id(), coupon.issueStartAt()));
		return new CouponResponse.CouponInfos(infos, pageInfo);
	}

	@Transactional(readOnly = true)
	public CouponResponse.MemberCouponInfos findMyCoupons(Long memberId, CursorRequest.CursorPageRequest request) {
		LocalDateTime now = timeProvider.now();
		Slice<MemberCouponProjection> slice = memberCouponRepository.findByMemberIdWithCursor(
			memberId, request.lastId(), request.lastCreatedAt(), PageRequest.of(0, request.size()));
		List<CouponResponse.MemberCouponInfo> infos = slice.getContent().stream()
			.map(memberCoupon -> CouponResponse.MemberCouponInfo.of(memberCoupon, now))
			.toList();
		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			slice.getContent(), slice.hasNext(), MemberCouponProjection::memberCouponId,
			MemberCouponProjection::createdAt);
		return new CouponResponse.MemberCouponInfos(infos, pageInfo);
	}
}
