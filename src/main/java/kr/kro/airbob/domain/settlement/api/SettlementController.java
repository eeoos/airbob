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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.settlement.dto.SettlementResponse;
import kr.kro.airbob.domain.settlement.entity.SettlementStatus;
import kr.kro.airbob.domain.settlement.service.SettlementService;
import lombok.RequiredArgsConstructor;

@Tag(name = "정산")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class SettlementController {

	private final SettlementService settlementService;

	private static final String MONTH_PATTERN = "^[1-9][0-9]{3}-(0[1-9]|1[0-2])$";

	// 호스트 본인 정산 목록 (settlement_month = 월초 날짜 범위)
	@Operation(operationId = "Settlement.getHostSettlements", summary = "내 정산 목록 조회",
		description = "로그인한 회원 본인의 저장된 정산을 월 오름차순으로 조회합니다. from·to는 YYYY-MM-DD이며 양 끝을 포함해 settlement_month(각 월의 1일)를 비교합니다. 예를 들어 from=2027-01-15이면 1월 정산은 제외됩니다. from은 to 이하여야 하며 연도는 1000~9999입니다. 페이지 구분 없이 전체 목록을 반환하고 정산이 없으면 빈 배열입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/v1/profile/host/settlements")
	public ResponseEntity<ApiResponse<List<SettlementResponse.HostSettlement>>> getHostSettlements(
		@Parameter(description = "정산 월초 날짜의 조회 시작일(포함). from ≤ to", example = "2027-01-01")
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@Parameter(description = "정산 월초 날짜의 조회 종료일(포함). 연도는 1000~9999", example = "2027-03-31")
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@CurrentMemberId Long hostId) {

		validateDateRange(from, to);
		return ResponseEntity.ok(ApiResponse.success(settlementService.getHostSettlements(hostId, from, to)));
	}

	// 호스트 대시보드 요약(정산 예정/누적 지급 총액·건수)
	@Operation(operationId = "Settlement.getHostSummary", summary = "내 정산 요약 조회",
		description = "로그인한 회원 본인의 저장된 정산 전체를 PENDING·PAID별로 합산합니다. 기간 필터는 없으며 건수는 예약 수가 아닌 정산 레코드 수입니다. 정산이 없으면 금액과 건수 모두 0입니다. PENDING에는 아직 마감되지 않은 월도 포함될 수 있습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@GetMapping("/v1/profile/host/settlements/summary")
	public ResponseEntity<ApiResponse<SettlementResponse.HostSummary>> getHostSummary(
		@CurrentMemberId Long hostId) {
		return ResponseEntity.ok(ApiResponse.success(settlementService.getHostSummary(hostId)));
	}

	// 호스트 본인 정산 상세(숙소별 내역)
	@Operation(operationId = "Settlement.getHostSettlementDetail", summary = "내 정산 상세 조회",
		description = "로그인한 회원 본인의 정산만 조회합니다. 다른 회원의 정산은 403/S004, 없는 정산은 404/S001입니다. 요약 금액은 저장된 정산 값이며 items는 조회 시점의 원장을 현재 숙소 소유자 기준으로 다시 집계합니다. 원장이나 숙소 소유자가 바뀌면 요약과 items 합계가 다를 수 있습니다. items는 숙소 ID 오름차순이며 수수료·지급액은 요약에만 있습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.SETTLEMENT_NOT_FOUND,
		ErrorCode.SETTLEMENT_ACCESS_DENIED,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/v1/profile/host/settlements/{settlementId}")
	public ResponseEntity<ApiResponse<SettlementResponse.SettlementDetail>> getHostSettlementDetail(
		@Parameter(description = "정산 목록 응답의 settlement_id", example = "101") @PathVariable Long settlementId,
		@CurrentMemberId Long hostId) {

		return ResponseEntity.ok(ApiResponse.success(
			settlementService.getSettlementDetail(settlementId, hostId)));
	}

	// 관리자: 월별 정산 조회 (status optional 필터)
	@Operation(operationId = "Settlement.getSettlements", summary = "월별 정산 조회",
		description = "month=YYYY-MM(1000-01~9999-12)의 저장된 정산을 호스트 ID 오름차순으로 조회합니다. status는 PENDING 또는 PAID이며 생략·빈 값이면 전체 상태입니다. 페이지 구분 없이 전체 목록을 반환하고 없으면 빈 배열입니다. 조회만으로 정산을 생성하거나 재집계하지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/v1/admin/settlements")
	public ResponseEntity<ApiResponse<List<SettlementResponse.AdminSettlement>>> getSettlements(
		@Parameter(description = "조회 월", example = "2027-01", schema = @Schema(pattern = MONTH_PATTERN)) @RequestParam String month,
		@Parameter(description = "PENDING(지급 전) 또는 PAID(지급 완료 기록). 생략·빈 값이면 전체") @RequestParam(required = false) SettlementStatus status) {

		return ResponseEntity.ok(ApiResponse.success(
			settlementService.getSettlements(parseMonth(month), status)));
	}

	// 관리자: 단월 정산 생성/재집계 (month = YYYY-MM)
	@Operation(operationId = "Settlement.generate", summary = "월 정산 생성·재집계",
		description = "month=YYYY-MM(1000-01~9999-12)의 결제 원장을 호스트별로 집계합니다. 새 정산은 PENDING, 기존 PENDING은 현재 수수료율로 갱신하며 PAID는 변경하지 않습니다. 현재·미래 월도 요청할 수 있고 원장이 없으면 새 정산을 만들지 않습니다. 동기 처리 후 200과 success=true만 반환합니다. 같은 월의 생성 잠금을 5초 안에 얻지 못하거나 대기가 중단되면 실행을 건너뛰어도 200이므로 월별 정산 조회로 결과를 확인합니다. 실제 송금은 하지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors(ErrorCode.INVALID_INPUT_VALUE)
	@PostMapping("/v1/admin/settlements/generate")
	public ResponseEntity<ApiResponse<Void>> generate(
		@Parameter(description = "생성·재집계 월", example = "2027-01", schema = @Schema(pattern = MONTH_PATTERN)) @RequestParam String month) {
		settlementService.generateMonth(parseMonth(month));
		return ResponseEntity.ok(ApiResponse.success());
	}

	// 관리자: 월 구간 백필 (from, to = YYYY-MM)
	@Operation(operationId = "Settlement.backfill", summary = "정산 기간 재집계",
		description = "from·to는 YYYY-MM(1000-01~9999-12)이며 from ≤ to인 구간의 양 끝 월을 포함해 순서대로 생성·재집계합니다. 월마다 별도 트랜잭션이므로 중간 실패 시 이미 완료한 월은 유지됩니다. 잠금 경합으로 건너뛴 월이 있어도 200일 수 있으며 PAID 정산은 변경하지 않습니다. 처리 결과·작업 ID는 반환하지 않으므로 월별 조회로 확인합니다. 실제 송금은 하지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors(ErrorCode.INVALID_INPUT_VALUE)
	@PostMapping("/v1/admin/settlements/backfill")
	public ResponseEntity<ApiResponse<Void>> backfill(
		@Parameter(description = "시작 월(포함)", example = "2027-01", schema = @Schema(pattern = MONTH_PATTERN)) @RequestParam String from,
		@Parameter(description = "종료 월(포함). from 이상", example = "2027-03", schema = @Schema(pattern = MONTH_PATTERN)) @RequestParam String to) {
		YearMonth first = parseMonth(from);
		YearMonth last = parseMonth(to);
		if (first.isAfter(last)) {
			throw new InvalidInputException("시작 월은 종료 월 이하여야 합니다.");
		}
		settlementService.backfill(first, last);
		return ResponseEntity.ok(ApiResponse.success());
	}

	// 관리자: 지급 처리(PENDING → PAID)
	@Operation(operationId = "Settlement.pay", summary = "정산 지급 처리",
		description = "외부 송금 없이 정산 상태를 PENDING에서 PAID로 변경하고 처리 시각·이력을 기록합니다. UTC 기준 현재 월보다 이전 월만 처리하며 금액을 재집계하지 않습니다. 이미 PAID이면 동시 요청에서도 409/S002, 마감 전이면 409/S003입니다. 지급 처리 후 재집계로 상태나 금액을 변경하지 않습니다. 요청 본문 없이 호출하며 성공 응답은 success=true입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.SETTLEMENT_NOT_FOUND,
		ErrorCode.SETTLEMENT_ALREADY_PAID,
		ErrorCode.INVALID_TYPE_VALUE,
		ErrorCode.SETTLEMENT_MONTH_NOT_CLOSED
	})
	@PostMapping("/v1/admin/settlements/{settlementId}/pay")
	public ResponseEntity<ApiResponse<Void>> pay(@Parameter(description = "정산 목록 응답의 settlement_id", example = "101") @PathVariable Long settlementId) {
		settlementService.markPaid(settlementId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	private YearMonth parseMonth(String value) {
		if (!value.matches(MONTH_PATTERN)) {
			throw new InvalidInputException("월은 YYYY-MM 형식이며 연도는 1000~9999여야 합니다.");
		}
		return YearMonth.parse(value);
	}

	private void validateDateRange(LocalDate from, LocalDate to) {
		if (from.isAfter(to) || from.getYear() < 1000 || from.getYear() > 9999
			|| to.getYear() < 1000 || to.getYear() > 9999) {
			throw new InvalidInputException("시작일은 종료일 이하여야 하며 연도는 1000~9999여야 합니다.");
		}
	}
}
