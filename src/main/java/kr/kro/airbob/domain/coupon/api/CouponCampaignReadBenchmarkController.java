package kr.kro.airbob.domain.coupon.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.service.CouponCampaignReadBenchmarkService;
import kr.kro.airbob.domain.coupon.service.CouponQueryService;
import lombok.RequiredArgsConstructor;

@RestController
@Profile("coupon-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/coupons/campaigns")
@RequiredArgsConstructor
public class CouponCampaignReadBenchmarkController {
	private final CouponQueryService baseline;
	private final CouponCampaignReadBenchmarkService candidate;
	private final BenchmarkAccessGuard accessGuard;

	@GetMapping("/count")
	public ApiResponse<CouponResponse.CouponInfos> count(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String token) {
		accessGuard.verify(token);
		return ApiResponse.success(baseline.findCouponCampaigns());
	}

	@GetMapping("/exists")
	public ApiResponse<CouponResponse.CouponInfos> exists(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String token) {
		accessGuard.verify(token);
		return ApiResponse.success(candidate.findWithRemainingUnits());
	}
}
