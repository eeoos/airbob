package kr.kro.airbob.domain.settlement.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.settlement.dto.SettlementResponse;
import kr.kro.airbob.domain.settlement.service.SettlementService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SettlementController {

	private final SettlementService settlementService;

	// 호스트 본인 정산 목록 (settlement_month = 월초 날짜 범위)
	@GetMapping("/v1/profile/host/settlements")
	public ResponseEntity<ApiResponse<List<SettlementResponse.HostSettlement>>> getHostSettlements(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		Long hostId = UserContext.get().id();
		return ResponseEntity.ok(ApiResponse.success(settlementService.getHostSettlements(hostId, from, to)));
	}

	// 관리자: 단월 정산 생성/재집계 (month = YYYY-MM)
	@PostMapping("/v1/admin/settlements/generate")
	public ResponseEntity<ApiResponse<Void>> generate(@RequestParam String month) {
		settlementService.generateMonth(YearMonth.parse(month));
		return ResponseEntity.ok(ApiResponse.success());
	}

	// 관리자: 월 구간 백필 (from, to = YYYY-MM)
	@PostMapping("/v1/admin/settlements/backfill")
	public ResponseEntity<ApiResponse<Void>> backfill(@RequestParam String from, @RequestParam String to) {
		settlementService.backfill(YearMonth.parse(from), YearMonth.parse(to));
		return ResponseEntity.ok(ApiResponse.success());
	}

	// 관리자: 지급 처리(PENDING → PAID)
	@PostMapping("/v1/admin/settlements/{settlementId}/pay")
	public ResponseEntity<ApiResponse<Void>> pay(@PathVariable Long settlementId) {
		settlementService.markPaid(settlementId);
		return ResponseEntity.ok(ApiResponse.success());
	}
}
