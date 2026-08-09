package kr.kro.airbob.domain.wishlist.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkAccessGuard;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkResponse;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBenchmarkService;

@RestController
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/admin/benchmarks/bulk-write/wishlist-delete")
public class WishlistDeleteBenchmarkController {

	private final WishlistDeleteBenchmarkService benchmarkService;
	private final BulkWriteBenchmarkAccessGuard accessGuard;

	public WishlistDeleteBenchmarkController(
		WishlistDeleteBenchmarkService benchmarkService,
		BulkWriteBenchmarkAccessGuard accessGuard
	) {
		this.benchmarkService = benchmarkService;
		this.accessGuard = accessGuard;
	}

	@PostMapping
	public ResponseEntity<ApiResponse<WishlistDeleteBenchmarkResponse>> run(
		@Valid @RequestBody WishlistDeleteBenchmarkRequest request,
		@RequestHeader(value = BulkWriteBenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId Long ownerId
	) {
		accessGuard.verify(benchmarkToken);
		WishlistDeleteBenchmarkResponse response = benchmarkService.run(ownerId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
