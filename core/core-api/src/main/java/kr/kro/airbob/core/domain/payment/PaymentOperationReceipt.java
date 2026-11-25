package kr.kro.airbob.core.domain.payment;

import java.util.UUID;

/**
 * 결제 승인·취소 요청을 받은 결과. 비동기 operation을 만들었거나 다시 찾은 경우 그 UID와 상태를 담고,
 * 0원 예약 취소처럼 operation 없이 바로 끝난 경우 completedSynchronously만 true다.
 */
public record PaymentOperationReceipt(
	UUID operationUid,
	PaymentOperationStatus status,
	boolean completedSynchronously) {

	public static PaymentOperationReceipt accepted(PaymentOperation operation) {
		return new PaymentOperationReceipt(operation.getOperationUid(), operation.getStatus(), false);
	}

	public static PaymentOperationReceipt completedWithoutOperation() {
		return new PaymentOperationReceipt(null, null, true);
	}
}
