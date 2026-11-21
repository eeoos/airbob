package kr.kro.airbob.domain.payment.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminRequest.MarkNotPaid;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminRequest.Reconciliation;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse.ActionAccepted;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse.ManualReviewQueue;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewCommandService;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewQueryService;
import lombok.RequiredArgsConstructor;

@Tag(name = "결제 운영")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/payment-operations")
public class PaymentOperationAdminController {

	private final PaymentOperationManualReviewQueryService queryService;
	private final PaymentOperationManualReviewCommandService commandService;

	@Operation(operationId = "PaymentOperationAdmin.findManualReviewQueue", summary = "수동 확인 결제 조회",
		description = """
			현재 수동 확인이 필요한 작업을 review_required_at·내부 ID 오름차순으로 조회합니다. limit은 기본 50, 1~100입니다.
			has_more는 추가 대상의 존재 여부이며 다음 페이지 커서는 제공하지 않습니다. 앞선 작업을 처리한 뒤 다시 조회합니다.
			available_actions의 허용 작업과 version을 사용하며, 처리 시점에 상태·버전을 다시 검증합니다. 대사 중인 작업은 목록에서 빠지고 다시 수동 확인이 필요하면 재등장합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/manual-review")
	public ResponseEntity<ApiResponse<ManualReviewQueue>> findManualReviewQueue(
		@Parameter(description = "조회할 작업 수. 커서 페이지가 아닌 가장 오래된 대기 작업 목록입니다.",
			schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "50"))
		@RequestParam(defaultValue = "50") int limit
	) {
		return ResponseEntity.ok(ApiResponse.success(queryService.findManualReviewQueue(limit)));
	}

	@Operation(operationId = "PaymentOperationAdmin.requestReconciliation", summary = "결제 대사 요청",
		description = """
			수동 확인 목록의 operation_uid와 version(expected_version)을 사용해 결제사의 현재 상태 조회를 접수합니다.
			MANUAL_REVIEW 상태이며 대사 진행 중이 아닌 작업만 허용합니다. 버전이 바뀌었거나 상태가 달라지면 409/P006이므로 목록을 다시 확인합니다.
			202 응답의 status=QUEUED는 조회 접수입니다. 승인·취소 명령을 다시 보내지 않으며 결과가 불명확하면 수동 확인 목록에 다시 표시됩니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "대사 조회 접수", useReturnTypeSchema = true,
		content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
			{"success":true,"data":{"operation_uid":"00000000-0000-0000-0000-000000000040","status":"QUEUED","version":8}}
			""")))
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.PAYMENT_OPERATION_NOT_FOUND,
		ErrorCode.PAYMENT_OPERATION_CONFLICT,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PostMapping("/{operationId}/reconciliation")
	public ResponseEntity<ApiResponse<ActionAccepted>> requestReconciliation(
		@Parameter(description = "수동 확인 목록의 operation_uid", example = "00000000-0000-0000-0000-000000000040")
		@PathVariable UUID operationId,
		@CurrentMemberId Long actorMemberId,
		@RequestBody @Valid Reconciliation request
	) {
		ActionAccepted response = ActionAccepted.from(
			commandService.requestReconciliation(operationId, actorMemberId, request.expectedVersion()));
		return ResponseEntity.accepted().body(ApiResponse.success(response));
	}

	@Operation(operationId = "PaymentOperationAdmin.markNotPaid", summary = "미결제 확정",
		description = """
			available_actions에 MARK_NOT_PAID가 있는 CONFIRM 작업만 처리합니다. 결제사 조회에서 결제 미존재가 확인되었고 수동 확인 상태인 작업이 대상입니다.
			목록의 version을 expected_version으로 보내고, 허용된 reason_code와 256자 이하 내부 증거 참조를 전달합니다. CANCEL 작업에는 적용할 수 없습니다.

			성공 응답은 202지만 미결제 확정은 응답 전에 반영됩니다. 관리자 상태는 DECLINED, 회원 상태는 FAILED이며 예약은 EXPIRED로 바뀌고 재고·쿠폰이 복구됩니다.
			버전·상태 충돌은 409/P006입니다. 503/P007이면 작업을 시작하지 못한 것이므로 상태·버전을 다시 확인한 뒤 재시도합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "미결제 확정 반영 완료", useReturnTypeSchema = true,
		content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
			{"success":true,"data":{"operation_uid":"00000000-0000-0000-0000-000000000040","status":"DECLINED","version":8}}
			""")))
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.PAYMENT_OPERATION_NOT_FOUND,
		ErrorCode.PAYMENT_OPERATION_CONFLICT,
		ErrorCode.PAYMENT_OPERATION_EXECUTION_FENCE_UNAVAILABLE,
		ErrorCode.RESERVATION_NOT_FOUND,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PostMapping("/{operationId}/mark-not-paid")
	public ResponseEntity<ApiResponse<ActionAccepted>> markNotPaid(
		@Parameter(description = "수동 확인 목록의 operation_uid", example = "00000000-0000-0000-0000-000000000040")
		@PathVariable UUID operationId,
		@CurrentMemberId Long actorMemberId,
		@RequestBody @Valid MarkNotPaid request
	) {
		ActionAccepted response = ActionAccepted.from(commandService.markNotPaid(
			operationId,
			actorMemberId,
			request.expectedVersion(),
			request.reasonCode(),
			request.evidenceReference()
		));
		return ResponseEntity.accepted().body(ApiResponse.success(response));
	}
}
