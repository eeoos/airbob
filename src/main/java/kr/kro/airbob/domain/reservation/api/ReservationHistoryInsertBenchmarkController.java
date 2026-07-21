package kr.kro.airbob.domain.reservation.api;

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
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkResponse;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkService;

@RestController
@Profile("bulk-write-benchmark")
@ConditionalOnProperty(prefix = "benchmark.bulk-write", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/admin/benchmarks/bulk-write/reservation-history-insert")
public class ReservationHistoryInsertBenchmarkController {

	private final ReservationHistoryInsertBenchmarkService benchmarkService;
	private final BulkWriteBenchmarkAccessGuard accessGuard;

	public ReservationHistoryInsertBenchmarkController(
		ReservationHistoryInsertBenchmarkService benchmarkService,
		BulkWriteBenchmarkAccessGuard accessGuard
	) {
		this.benchmarkService = benchmarkService;
		this.accessGuard = accessGuard;
	}

	@PostMapping
	public ResponseEntity<ApiResponse<ReservationHistoryInsertBenchmarkResponse>> run(
		@Valid @RequestBody ReservationHistoryInsertBenchmarkRequest request,
		@RequestHeader(value = BulkWriteBenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken
	) {
		accessGuard.verify(benchmarkToken);
		return ResponseEntity.ok(ApiResponse.success(benchmarkService.run(request)));
	}
}
