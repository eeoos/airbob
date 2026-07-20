package kr.kro.airbob.domain.statistics.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;
import kr.kro.airbob.domain.statistics.service.RevenueStatsService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/stats")
public class RevenueStatsController {

	private final RevenueStatsService revenueStatsService;

	// 운영 읽기 경로(after): 일일 사전집계 테이블을 조회한다.
	@GetMapping("/revenue")
	public ResponseEntity<ApiResponse<RevenueStatsResponse.DailyRevenues>> getDailyRevenue(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		RevenueStatsResponse.DailyRevenues response = revenueStatsService.getDailyRevenue(from, to);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	// [from, to] 구간 사전집계 백필(수동 트리거). 측정 전 데이터 적재용.
	@PostMapping("/revenue/recompute")
	public ResponseEntity<ApiResponse<Void>> recompute(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		revenueStatsService.backfill(from, to);
		return ResponseEntity.ok(ApiResponse.success());
	}
}
