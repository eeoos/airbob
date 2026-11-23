package kr.kro.airbob.core.domain.coupon;

import java.time.LocalDateTime;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.coupon.repository.CouponIssueUnitStore;
import kr.kro.airbob.core.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.core.domain.coupon.repository.MemberCouponRepository;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import lombok.RequiredArgsConstructor;

/** 쿠폰 정책·발급 내역 조회 구현 컴포넌트. 발급 판단에 쓰는 시각은 MySQL UTC 시각이다. */
@Component
@RequiredArgsConstructor
public class CouponReader {

	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final CouponIssueUnitStore units;

	public LocalDateTime databaseNow() {
		return units.databaseNow();
	}

	/** 관리자 변경용으로 쿠폰 정책을 FOR UPDATE로 잠근다. */
	public Coupon readForUpdate(Long couponId) {
		return couponRepository.findByIdForUpdate(couponId).orElseThrow(CouponNotFoundException::new);
	}

	public long countIssued(Long couponId) {
		return memberCouponRepository.countByCouponId(couponId);
	}

	public Slice<CouponCampaignProjection> readCampaigns(
		LocalDateTime now, CursorRequest.CouponCampaignCursorPageRequest request) {
		return couponRepository.findCampaignsWithCursor(
			now, request.lastId(), request.lastIssueStartAt(), PageRequest.of(0, request.size()));
	}

	public Slice<MemberCouponProjection> readMemberCoupons(Long memberId, CursorRequest.CursorPageRequest request) {
		return memberCouponRepository.findByMemberIdWithCursor(
			memberId, request.lastId(), request.lastCreatedAt(), PageRequest.of(0, request.size()));
	}
}
