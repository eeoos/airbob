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
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.coupon.service.CouponDbIssueService;
import kr.kro.airbob.domain.coupon.service.CouponSkipLockedIssueService;
import kr.kro.airbob.domain.coupon.common.CouponIssueStrategy;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Profile("coupon-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/coupons")
public class CouponBenchmarkController {

	private final CouponDbIssueService dbIssueService;
	private final BenchmarkAccessGuard accessGuard;
	private final CouponSkipLockedIssueService skipLockedIssueService;

	@PostMapping("/{couponId}/issue/db")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithConditionalUpdate(
		@PathVariable Long couponId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		accessGuard.verify(benchmarkToken);
		dbIssueService.issue(couponId, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}

	@PostMapping("/{couponId}/issue/skip-surrogate-rr")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithSurrogateRr(
		@PathVariable Long couponId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		return issueWithSkipLocked(couponId, memberId, benchmarkToken, CouponIssueStrategy.SKIP_SURROGATE_RR);
	}

	@PostMapping("/{couponId}/issue/skip-surrogate-rc")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithSurrogateRc(
		@PathVariable Long couponId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		return issueWithSkipLocked(couponId, memberId, benchmarkToken, CouponIssueStrategy.SKIP_SURROGATE_RC);
	}

	@PostMapping("/{couponId}/issue/skip-composite-rr")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithCompositeRr(
		@PathVariable Long couponId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		return issueWithSkipLocked(couponId, memberId, benchmarkToken, CouponIssueStrategy.SKIP_COMPOSITE_RR);
	}

	@PostMapping("/{couponId}/issue/skip-composite-rc")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithCompositeRc(
		@PathVariable Long couponId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long memberId
	) {
		return issueWithSkipLocked(couponId, memberId, benchmarkToken, CouponIssueStrategy.SKIP_COMPOSITE_RC);
	}

	private ResponseEntity<ApiResponse<Void>> issueWithSkipLocked(
		Long couponId, Long memberId, String benchmarkToken, CouponIssueStrategy strategy
	) {
		accessGuard.verify(benchmarkToken);
		// The issuance transaction validates the stored strategy using its locked coupon read.
		skipLockedIssueService.issue(couponId, memberId, strategy);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}
}
