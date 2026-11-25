package kr.kro.airbob.core.api.controller.v1.payment.response;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.core.domain.payment.PaymentOperationDetail;
import kr.kro.airbob.core.domain.payment.PaymentOperationDetailRow;
import kr.kro.airbob.core.domain.payment.PaymentOperationReceipt;
import kr.kro.airbob.core.domain.payment.PaymentOperationStatus;
import kr.kro.airbob.core.domain.payment.PaymentOperationType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOperationResponse {

	public enum Status {
		PENDING, PROCESSING, SUCCEEDED, FAILED, REQUIRES_REVIEW;

		public static Status from(PaymentOperationStatus status) {
			return switch (status) {
				case QUEUED, WAITING_RETRY -> PENDING;
				case EXECUTING -> PROCESSING;
				case APPLIED -> SUCCEEDED;
				case DECLINED -> FAILED;
				case MANUAL_REVIEW -> REQUIRES_REVIEW;
			};
		}
	}

	public enum NextAction {
		POLL,
		START_NEW_CHECKOUT,
		RETRY_CANCELLATION,
		CONTACT_SUPPORT,
		NONE;

		public static NextAction from(PaymentOperationDetail.NextAction action) {
			return switch (action) {
				case POLL -> POLL;
				case START_NEW_CHECKOUT -> START_NEW_CHECKOUT;
				case RETRY_CANCELLATION -> RETRY_CANCELLATION;
				case CONTACT_SUPPORT -> CONTACT_SUPPORT;
				case NONE -> NONE;
			};
		}
	}

	@Schema(name = "PaymentOperationResponseAccepted")
	public record Accepted(
		@Schema(description = "승인 작업 UUID. 예약 UID와 별개입니다.", example = "00000000-0000-0000-0000-000000000040", requiredMode = Schema.RequiredMode.REQUIRED) UUID operationId,
		@Schema(description = "작업의 현재 회원용 상태. 재요청에는 이미 완료된 상태도 반환합니다.", example = "PENDING", requiredMode = Schema.RequiredMode.REQUIRED) Status status,
		@Schema(description = "같은 API 서버에서 조회할 상대 URL", example = "/api/v1/payment-operations/00000000-0000-0000-0000-000000000040", requiredMode = Schema.RequiredMode.REQUIRED) String statusUrl) {
		public static Accepted from(PaymentOperationReceipt receipt) {
			UUID operationUid = receipt.operationUid();
			return new Accepted(operationUid, Status.from(receipt.status()),
				"/api/v1/payment-operations/" + operationUid);
		}
	}

	@Schema(name = "PaymentOperationResponseCancellation")
	public record Cancellation(
		UUID operationId,
		Status status,
		@Schema(description = "결제 작업 상태를 조회할 상대 URL") String statusUrl,
		boolean completedSynchronously) {
		public static Cancellation from(PaymentOperationReceipt receipt) {
			if (receipt.completedSynchronously()) {
				return completed();
			}
			UUID operationUid = receipt.operationUid();
			return new Cancellation(
				operationUid,
				Status.from(receipt.status()),
				"/api/v1/payment-operations/" + operationUid,
				false);
		}

		public static Cancellation completed() {
			return new Cancellation(null, Status.SUCCEEDED, null, true);
		}
	}

	@Schema(name = "PaymentOperationResponseDetail", example = """
		{"operation_id":"00000000-0000-0000-0000-000000000040","order_id":"00000000-0000-0000-0000-000000000020",
		 "status":"PENDING","failure_code":null,"updated_at":"2027-01-01T00:00:00Z","next_action":"POLL",
		 "retry_after_seconds":2,"user_message":"결제 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.",
		 "server_time":"2027-01-01T00:00:02Z","user_failure_code":null}
		""")
	public record Detail(
		@Schema(description = "승인 또는 취소 작업 UUID", example = "00000000-0000-0000-0000-000000000040", requiredMode = Schema.RequiredMode.REQUIRED) UUID operationId,
		@Schema(description = "예약 UID와 같은 주문 ID", example = "00000000-0000-0000-0000-000000000020", requiredMode = Schema.RequiredMode.REQUIRED) UUID orderId,
		@Schema(description = "회원용 작업 상태. 결제 조회 응답의 DONE·CANCELED 상태와 구분합니다.", example = "PENDING", requiredMode = Schema.RequiredMode.REQUIRED) Status status,
		@Schema(description = "FAILED·REQUIRES_REVIEW에서 제공할 수 있는 내부 진단 코드. 그 외 null이며 사용자 안내에는 user_message·user_failure_code를 사용합니다.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String failureCode,
		@Schema(description = "작업이 마지막으로 변경된 UTC 시각", example = "2027-01-01T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
		@Schema(description = "현재 예약 상태·체크인 시각을 고려한 다음 행동. status보다 이 값을 우선합니다.", example = "POLL", requiredMode = Schema.RequiredMode.REQUIRED) NextAction nextAction,
		@Schema(description = "재조회 권장 간격(초). 진행 중에는 2~30, REQUIRES_REVIEW에는 30, SUCCEEDED·FAILED에는 null입니다. 값이 있어도 승인·취소 재요청을 뜻하지 않습니다.", nullable = true, minimum = "2", maximum = "30", example = "2", requiredMode = Schema.RequiredMode.REQUIRED) Long retryAfterSeconds,
		@Schema(description = "현재 상태와 다음 행동에 맞는 사용자 안내", example = "결제 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.", requiredMode = Schema.RequiredMode.REQUIRED) String userMessage,
		@Schema(description = "서버의 현재 UTC 시각", example = "2027-01-01T00:00:02Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant serverTime,
		@Schema(description = "FAILED: PAYMENT_DECLINED 또는 PAYMENT_CANCELLATION_DECLINED, REQUIRES_REVIEW: PAYMENT_REVIEW_REQUIRED. 그 외 null입니다.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String userFailureCode) {
		public static Detail from(PaymentOperationDetail detail) {
			PaymentOperationDetailRow operation = detail.operation();
			PaymentOperationStatus operationStatus = operation.status();
			NextAction nextAction = NextAction.from(detail.nextAction());
			return new Detail(
				operation.operationUid(),
				operation.reservationUid(),
				Status.from(operationStatus),
				operationStatus == PaymentOperationStatus.DECLINED
					|| operationStatus == PaymentOperationStatus.MANUAL_REVIEW
						? operation.failureCode() : null,
				operation.updatedAt().toInstant(ZoneOffset.UTC),
				nextAction,
				detail.retryAfterSeconds(),
				userMessage(operationStatus, operation.operationType(), nextAction),
				detail.serverTime(),
				userFailureCode(operationStatus, operation.operationType()));
		}

		private static String userFailureCode(
			PaymentOperationStatus status,
			PaymentOperationType operationType) {
			return switch (status) {
				case DECLINED -> operationType == PaymentOperationType.CANCEL
					? "PAYMENT_CANCELLATION_DECLINED"
					: "PAYMENT_DECLINED";
				case MANUAL_REVIEW -> "PAYMENT_REVIEW_REQUIRED";
				case QUEUED, EXECUTING, WAITING_RETRY, APPLIED -> null;
			};
		}

		private static String userMessage(
			PaymentOperationStatus status,
			PaymentOperationType operationType,
			NextAction nextAction) {
			if (status == PaymentOperationStatus.APPLIED) {
				return operationType == PaymentOperationType.CANCEL
					? "예약 취소가 완료되었습니다."
					: "결제가 완료되어 예약이 확정되었습니다.";
			}
			return switch (nextAction) {
				case POLL -> operationType == PaymentOperationType.CANCEL
					? "예약 취소 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요."
					: "결제 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.";
				case START_NEW_CHECKOUT ->
					"결제가 완료되지 않았습니다. 새 견적을 받은 뒤 예약을 다시 진행해 주세요.";
				case RETRY_CANCELLATION -> "결제 취소가 완료되지 않았습니다. 취소를 다시 요청해 주세요.";
				case CONTACT_SUPPORT -> operationType == PaymentOperationType.CANCEL
					? "예약 취소 상태에 추가 확인이 필요합니다. 고객센터에 문의해 주세요."
					: "결제 상태에 추가 확인이 필요합니다. 고객센터에 문의해 주세요.";
				case NONE -> "현재 예약 상태를 확인해 주세요.";
			};
		}
	}
}
