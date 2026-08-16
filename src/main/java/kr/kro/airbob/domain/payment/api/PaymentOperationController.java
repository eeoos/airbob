package kr.kro.airbob.domain.payment.api;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Detail;
import kr.kro.airbob.domain.payment.service.PaymentOperationQueryService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PaymentOperationController {

	private final PaymentOperationQueryService paymentOperationQueryService;

	@GetMapping("/api/v1/payment-operations/{operationId}")
	public ResponseEntity<ApiResponse<Detail>> find(
		@PathVariable UUID operationId,
		@CurrentMemberId Long memberId
	) {
		return ResponseEntity.ok(ApiResponse.success(paymentOperationQueryService.find(operationId, memberId)));
	}
}
