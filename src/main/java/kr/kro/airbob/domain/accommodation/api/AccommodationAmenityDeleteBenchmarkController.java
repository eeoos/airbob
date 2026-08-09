package kr.kro.airbob.domain.accommodation.api;

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
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkResponse;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBenchmarkService;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;

@RestController
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/admin/benchmarks/bulk-write/accommodation-amenity-delete")
public class AccommodationAmenityDeleteBenchmarkController {

	private final AccommodationAmenityDeleteBenchmarkService benchmarkService;
	private final BulkWriteBenchmarkAccessGuard accessGuard;

	public AccommodationAmenityDeleteBenchmarkController(
		AccommodationAmenityDeleteBenchmarkService benchmarkService,
		BulkWriteBenchmarkAccessGuard accessGuard
	) {
		this.benchmarkService = benchmarkService;
		this.accessGuard = accessGuard;
	}

	@PostMapping
	public ResponseEntity<ApiResponse<AccommodationAmenityDeleteBenchmarkResponse>> run(
		@Valid @RequestBody AccommodationAmenityDeleteBenchmarkRequest request,
		@RequestHeader(value = BulkWriteBenchmarkAccessGuard.HEADER_NAME, required = false)
		String benchmarkToken,
		@CurrentMemberId Long ownerId
	) {
		accessGuard.verify(benchmarkToken);
		return ResponseEntity.ok(ApiResponse.success(benchmarkService.run(ownerId, request)));
	}
}
