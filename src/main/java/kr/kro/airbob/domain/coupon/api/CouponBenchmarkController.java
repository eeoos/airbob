package kr.kro.airbob.domain.coupon.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.coupon.service.CouponLockIssueService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Profile("coupon-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/coupons")
public class CouponBenchmarkController {

	private final CouponLockIssueService lockIssueService;
	private final BenchmarkAccessGuard accessGuard;

	@PostMapping("/{couponId}/issue")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithLock(
		@PathVariable Long couponId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken
	) {
		accessGuard.verify(benchmarkToken);
		Long memberId = UserContext.get().id();
		lockIssueService.issue(couponId, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}
}
