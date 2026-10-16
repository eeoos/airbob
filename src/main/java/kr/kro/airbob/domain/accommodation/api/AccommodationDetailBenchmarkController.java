package kr.kro.airbob.domain.accommodation.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.service.AccommodationDetailBenchmarkService;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Profile("read-model-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/accommodations")
public class AccommodationDetailBenchmarkController {

	private final AccommodationDetailBenchmarkService benchmarkService;
	private final BenchmarkAccessGuard accessGuard;

	@GetMapping("/{accommodationId}")
	public ResponseEntity<ApiResponse<AccommodationResponse.DetailInfo>> findAccommodationBefore(
		@PathVariable Long accommodationId,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken,
		@CurrentMemberId(required = false) Long viewerId
	) {
		accessGuard.verify(benchmarkToken);
		AccommodationResponse.DetailInfo response =
			benchmarkService.findAccommodationBefore(accommodationId, viewerId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
