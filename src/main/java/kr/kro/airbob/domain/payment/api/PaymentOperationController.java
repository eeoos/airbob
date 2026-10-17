package kr.kro.airbob.domain.payment.api;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Detail;
import kr.kro.airbob.domain.payment.service.PaymentOperationQueryService;
import lombok.RequiredArgsConstructor;

@Tag(name = "결제")
@RestController
@RequiredArgsConstructor
public class PaymentOperationController {

	private final PaymentOperationQueryService paymentOperationQueryService;

	@Operation(operationId = "PaymentOperation.find", summary = "결제 작업 상태 조회",
		description = """
			작업을 요청한 회원 본인의 결제 승인 또는 취소 결과를 조회합니다. operationId는 승인 접수 응답의 operation_id 또는 취소 응답 Location에 포함된 UUID입니다.
			PENDING·PROCESSING은 진행 중, SUCCEEDED는 해당 승인 또는 취소 성공, FAILED는 최종 실패, REQUIRES_REVIEW는 운영자 확인 필요 상태입니다.

			next_action을 우선합니다. POLL이면 retry_after_seconds(2~30초) 후 다시 조회하고, START_NEW_CHECKOUT이면 새 견적부터 진행합니다.
			RETRY_CANCELLATION이면 취소를 재요청하고, CONTACT_SUPPORT이면 user_message와 문의 안내를 표시하며, NONE이면 예약 상세를 확인합니다.
			REQUIRES_REVIEW에서도 retry_after_seconds=30이지만 승인·취소 요청을 자동으로 다시 보내지 않습니다.
			현재 예약 상태와 체크인 시각에 따라 next_action이 달라질 수 있으므로 status만으로 다음 행동을 고정하지 않습니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true,
		headers = @Header(name = "Cache-Control", description = "no-store", schema = @Schema(type = "string", example = "no-store")))
	@ApiErrors({
		ErrorCode.PAYMENT_OPERATION_NOT_FOUND,
		ErrorCode.PAYMENT_ACCESS_DENIED,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@GetMapping("/api/v1/payment-operations/{operationId}")
	public ResponseEntity<ApiResponse<Detail>> find(
		@Parameter(description = "결제 작업 UUID. 예약 UID나 결제 시도 ID가 아닙니다.", example = "00000000-0000-0000-0000-000000000040")
		@PathVariable UUID operationId,
		@CurrentMemberId Long memberId
	) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(paymentOperationQueryService.find(operationId, memberId)));
	}
}
