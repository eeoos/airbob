package kr.kro.airbob.domain.coupon.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.repository.MemberCouponRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponQueryService {

	private final CouponRepository couponRepository;
	private final MemberCouponRepository memberCouponRepository;
	private final CouponTimeProvider timeProvider;
	private final CouponRedisStockManager stockManager;

	@Transactional(readOnly = true)
	public CouponResponse.CouponInfos findCouponCampaigns() {
		LocalDateTime now = timeProvider.fromEpochMilli(stockManager.currentEpochMillis());
		var infos = couponRepository.findCampaigns(now).stream()
			.map(coupon -> CouponResponse.CouponInfo.of(coupon, now))
			.toList();
		return new CouponResponse.CouponInfos(infos);
	}

	@Transactional(readOnly = true)
	public CouponResponse.MemberCouponInfos findMyCoupons(Long memberId) {
		LocalDateTime now = timeProvider.now();
		var infos = memberCouponRepository.findByMemberIdOrderByCreatedAtDescIdDesc(memberId).stream()
			.map(memberCoupon -> CouponResponse.MemberCouponInfo.of(memberCoupon, now))
			.toList();
		return new CouponResponse.MemberCouponInfos(infos);
	}
}
