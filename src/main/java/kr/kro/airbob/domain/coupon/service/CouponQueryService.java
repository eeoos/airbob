package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CouponCampaignCursorData;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.domain.coupon.repository.CouponIssueUnitStore;
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
		var slice = couponRepository.findCampaignsWithCursor(
			now, request.lastId(), request.lastIssueStartAt(), PageRequest.of(0, request.size()));
		var infos = slice.getContent().stream()
			.map(coupon -> CouponResponse.CouponInfo.of(coupon, now))
			.toList();
		var pageInfo = cursorPageInfoCreator.createPageInfo(
			slice.getContent(), slice.hasNext(),
			coupon -> new CouponCampaignCursorData(coupon.id(), coupon.issueStartAt()));
		return new CouponResponse.CouponInfos(infos, pageInfo);
	}

	@Transactional(readOnly = true)
	public CouponResponse.MemberCouponInfos findMyCoupons(Long memberId, CursorRequest.CursorPageRequest request) {
		LocalDateTime now = timeProvider.now();
		var slice = memberCouponRepository.findByMemberIdWithCursor(
			memberId, request.lastId(), request.lastCreatedAt(), PageRequest.of(0, request.size()));
		var infos = slice.getContent().stream()
			.map(memberCoupon -> CouponResponse.MemberCouponInfo.of(memberCoupon, now))
			.toList();
		var pageInfo = cursorPageInfoCreator.createPageInfo(
			slice.getContent(), slice.hasNext(), MemberCouponProjection::memberCouponId, MemberCouponProjection::createdAt);
		return new CouponResponse.MemberCouponInfos(infos, pageInfo);
	}
}
