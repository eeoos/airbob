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

import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminRequest.MarkNotPaid;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminRequest.Reconciliation;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse.ActionAccepted;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse.ManualReviewQueue;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewCommandService;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewQueryService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/payment-operations")
public class PaymentOperationAdminController {

	private final PaymentOperationManualReviewQueryService queryService;
	private final PaymentOperationManualReviewCommandService commandService;

	@GetMapping("/manual-review")
	public ResponseEntity<ApiResponse<ManualReviewQueue>> findManualReviewQueue(
		@RequestParam(defaultValue = "50") int limit
	) {
		return ResponseEntity.ok(ApiResponse.success(queryService.findManualReviewQueue(limit)));
	}

	@PostMapping("/{operationId}/reconciliation")
	public ResponseEntity<ApiResponse<ActionAccepted>> requestReconciliation(
		@PathVariable UUID operationId,
		@CurrentMemberId Long actorMemberId,
		@RequestBody @Valid Reconciliation request
	) {
		ActionAccepted response = ActionAccepted.from(
			commandService.requestReconciliation(operationId, actorMemberId, request.expectedVersion()));
		return ResponseEntity.accepted().body(ApiResponse.success(response));
	}

	@PostMapping("/{operationId}/mark-not-paid")
	public ResponseEntity<ApiResponse<ActionAccepted>> markNotPaid(
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
