package kr.kro.airbob.domain.statistics.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;
import kr.kro.airbob.domain.statistics.service.RevenueStatsService;
import lombok.RequiredArgsConstructor;

@Tag(name = "매출 통계")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/stats")
public class RevenueStatsController {

	private final RevenueStatsService revenueStatsService;

	@Operation(operationId = "RevenueStats.getDailyRevenue", summary = "일별 매출 조회",
		description = "from과 to를 포함하는 UTC 날짜 구간의 사전 집계 결과를 날짜 오름차순으로 조회합니다. "
			+ "전체 숙소의 결제 승인액·환불액·차액과 거래 원장 행 수를 합산하며, 통화별 구분이나 환산은 하지 않습니다. "
			+ "실시간 결제 조회가 아니므로 아직 집계하지 않은 거래는 반영되지 않습니다. "
			+ "집계 행이 없는 날짜는 생략하며, 결과가 없으면 items는 빈 배열입니다. 페이지네이션은 없습니다. "
			+ "from ≤ to이고 양쪽 연도는 1000~9999여야 합니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/revenue")
	public ResponseEntity<ApiResponse<RevenueStatsResponse.DailyRevenues>> getDailyRevenue(
		@Parameter(description = "조회 시작일, 포함. UTC 날짜 YYYY-MM-DD", example = "2027-01-01")
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@Parameter(description = "조회 종료일, 포함. 시작일 이상인 UTC 날짜 YYYY-MM-DD", example = "2027-01-31")
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		validateDateRange(from, to);
		RevenueStatsResponse.DailyRevenues response = revenueStatsService.getDailyRevenue(from, to);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(operationId = "RevenueStats.recompute", summary = "매출 통계 재집계",
		description = "from과 to를 포함하는 UTC 날짜 구간을 결제 거래 원장에서 동기 재집계합니다. "
			+ "각 날짜의 기존 집계 행을 삭제한 뒤 다시 저장하며, 거래가 없는 날짜는 집계 행도 제거됩니다. "
			+ "전체 구간이 한 트랜잭션이므로 중간 실패 시 이번 요청의 모든 변경이 롤백됩니다. "
			+ "응답은 처리가 끝난 뒤 반환하며, data·처리 건수·작업 ID는 없습니다. 요청 본문 없이 from/to 쿼리만 전달합니다. "
			+ "from ≤ to이고 양쪽 연도는 1000~9999여야 합니다. 오늘·미래 날짜도 허용합니다. "
			+ "자동 집계는 매일 04:00 UTC에 전일을 처리하므로 과거 날짜에 늦게 반영된 거래는 해당 날짜를 다시 집계해야 합니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재집계 완료. success만 반환", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PostMapping("/revenue/recompute")
	public ResponseEntity<ApiResponse<Void>> recompute(
		@Parameter(description = "재집계 시작일, 포함. UTC 날짜 YYYY-MM-DD", example = "2027-01-01")
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@Parameter(description = "재집계 종료일, 포함. 시작일 이상인 UTC 날짜 YYYY-MM-DD", example = "2027-01-31")
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		validateDateRange(from, to);
		revenueStatsService.backfill(from, to);
		return ResponseEntity.ok(ApiResponse.success());
	}

	private void validateDateRange(LocalDate from, LocalDate to) {
		if (from.isAfter(to) || from.getYear() < 1000 || from.getYear() > 9999
			|| to.getYear() < 1000 || to.getYear() > 9999) {
			throw new InvalidInputException("시작일은 종료일 이하여야 하며 연도는 1000~9999여야 합니다.");
		}
	}
}
