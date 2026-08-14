package kr.kro.airbob.domain.payment.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOperationResponse {

	public enum Status {
		PENDING, PROCESSING, SUCCEEDED, FAILED, REQUIRES_REVIEW;

		public static Status from(PaymentOperationStatus status) {
			return switch (status) {
				case READY, RETRY_WAIT -> PENDING;
				case EXECUTING, OUTCOME_UNKNOWN -> PROCESSING;
				case APPLIED -> SUCCEEDED;
				case DECLINED -> FAILED;
				case MANUAL_REVIEW -> REQUIRES_REVIEW;
			};
		}
	}

	public record Accepted(UUID operationId, Status status, String statusUrl) {
		public static Accepted from(PaymentOperation operation) {
			UUID operationUid = operation.getOperationUid();
			return new Accepted(operationUid, Status.from(operation.getStatus()),
				"/api/v1/payment-operations/" + operationUid);
		}
	}

	public record Detail(UUID operationId, UUID orderId, Status status, String failureCode, Instant updatedAt) {
		public static Detail from(PaymentOperation operation) {
			PaymentOperationStatus operationStatus = operation.getStatus();
			return new Detail(
				operation.getOperationUid(),
				operation.getReservation().getReservationUid(),
				Status.from(operationStatus),
				operationStatus == PaymentOperationStatus.DECLINED || operationStatus == PaymentOperationStatus.MANUAL_REVIEW
					? operation.getFailureCode() : null,
				operation.getUpdatedAt().toInstant(ZoneOffset.UTC)
			);
		}
	}
}
