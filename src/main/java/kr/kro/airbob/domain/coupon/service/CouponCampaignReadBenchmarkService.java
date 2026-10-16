package kr.kro.airbob.domain.coupon.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import lombok.RequiredArgsConstructor;

@Service
@Profile("coupon-benchmark")
@RequiredArgsConstructor
public class CouponCampaignReadBenchmarkService {
	private final CouponRepository coupons;

	@Transactional(readOnly = true)
	public CouponResponse.CouponInfos findWithRemainingUnits() {
		var now = coupons.findDatabaseUtcNow();
		return new CouponResponse.CouponInfos(coupons.findCampaignsWithRemainingUnits(now).stream()
			.map(coupon -> coupon.toResponse(now)).toList());
	}
}
