package kr.kro.airbob.domain.statistics.api;

import java.time.LocalDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;
import kr.kro.airbob.domain.statistics.service.RevenueStatsBenchmarkService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Profile("read-model-benchmark")
@ConditionalOnProperty(prefix = "benchmark.read-model", name = "enabled", havingValue = "true")
@RequestMapping("/api/v2/admin/stats")
public class RevenueStatsBenchmarkController {

	private final RevenueStatsBenchmarkService benchmarkService;
	private final BenchmarkAccessGuard accessGuard;

	@GetMapping("/revenue")
	public ResponseEntity<ApiResponse<RevenueStatsResponse.DailyRevenues>> getDailyRevenueBefore(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestHeader(value = BenchmarkAccessGuard.HEADER_NAME, required = false) String benchmarkToken
	) {
		accessGuard.verify(benchmarkToken);
		RevenueStatsResponse.DailyRevenues response =
			benchmarkService.getDailyRevenueBefore(from, to);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
