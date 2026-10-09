package kr.kro.airbob.domain.coupon.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.coupon.dto.CouponBenchmarkFixture;
import kr.kro.airbob.domain.coupon.service.CouponBenchmarkFixtureService;
import lombok.RequiredArgsConstructor;

@RestController
@Profile("coupon-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/admin/coupons/benchmark/fixtures")
@RequiredArgsConstructor
public class CouponBenchmarkFixtureController {

	private final CouponBenchmarkFixtureService service;
	private final BenchmarkAccessGuard accessGuard;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<CouponBenchmarkFixture.Created> create(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String token,
		@Valid @RequestBody CouponBenchmarkFixture.Create request) {
		accessGuard.verify(token);
		return ApiResponse.success(service.create(request));
	}

	@GetMapping("/{couponId}")
	public ApiResponse<CouponBenchmarkFixture.State> state(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String token,
		@PathVariable Long couponId, @RequestParam("run_id") String runId) {
		accessGuard.verify(token);
		return ApiResponse.success(service.state(couponId, runId));
	}

	@DeleteMapping("/{couponId}")
	public ApiResponse<Void> close(
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String token,
		@PathVariable Long couponId, @RequestParam("run_id") String runId) {
		accessGuard.verify(token);
		service.close(couponId, runId);
		return ApiResponse.success();
	}
}
