package kr.kro.airbob.domain.payment.dto;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
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
		NONE
	}

	public record Accepted(UUID operationId, Status status, String statusUrl) {
		public static Accepted from(PaymentOperation operation) {
			UUID operationUid = operation.getOperationUid();
			return new Accepted(operationUid, Status.from(operation.getStatus()),
				"/api/v1/payment-operations/" + operationUid);
		}
	}

	public record Cancellation(
		UUID operationId,
		Status status,
		String statusUrl,
		boolean completedSynchronously
	) {
		public static Cancellation accepted(PaymentOperation operation) {
			UUID operationUid = operation.getOperationUid();
			return new Cancellation(
				operationUid,
				Status.from(operation.getStatus()),
				"/api/v1/payment-operations/" + operationUid,
				false
			);
		}

		public static Cancellation completed() {
			return new Cancellation(null, Status.SUCCEEDED, null, true);
		}
	}

	public record Detail(
		UUID operationId,
		UUID orderId,
		Status status,
		String failureCode,
		Instant updatedAt,
		NextAction nextAction,
		Long retryAfterSeconds,
		String userMessage,
		Instant serverTime,
		String userFailureCode
	) {
		private static final long MIN_POLL_INTERVAL_SECONDS = 2L;
		private static final long MAX_POLL_INTERVAL_SECONDS = 30L;
		private static final Duration MAX_POLL_INTERVAL =
			Duration.ofSeconds(MAX_POLL_INTERVAL_SECONDS);

		public static Detail from(PaymentOperation operation, Instant serverTime) {
			PaymentOperationStatus operationStatus = operation.getStatus();
			NextAction nextAction = nextAction(operation, serverTime);
			return new Detail(
				operation.getOperationUid(),
				operation.getReservation().getReservationUid(),
				Status.from(operationStatus),
				operationStatus == PaymentOperationStatus.DECLINED
					|| operationStatus == PaymentOperationStatus.MANUAL_REVIEW
					? operation.getFailureCode() : null,
				operation.getUpdatedAt().toInstant(ZoneOffset.UTC),
				nextAction,
				retryAfterSeconds(operation, serverTime),
				userMessage(operationStatus, operation.getOperationType(), nextAction),
				serverTime,
				userFailureCode(operationStatus, operation.getOperationType())
			);
		}

		private static NextAction nextAction(PaymentOperation operation, Instant serverTime) {
			return switch (operation.getStatus()) {
				case QUEUED, EXECUTING, WAITING_RETRY -> NextAction.POLL;
				case APPLIED -> NextAction.NONE;
				case DECLINED -> declinedNextAction(operation, serverTime);
				case MANUAL_REVIEW -> NextAction.CONTACT_SUPPORT;
			};
		}

		private static NextAction declinedNextAction(
			PaymentOperation operation,
			Instant serverTime
		) {
			Reservation reservation = operation.getReservation();
			boolean beforeCheckIn = reservation.getCheckInAt() != null
				&& serverTime.isBefore(reservation.getCheckInAt());
			if (operation.getOperationType() == PaymentOperationType.CONFIRM) {
				return reservation.effectiveStatus(serverTime) == ReservationStatus.EXPIRED && beforeCheckIn
					? NextAction.START_NEW_CHECKOUT
					: NextAction.NONE;
			}
			if (reservation.getStatus() != ReservationStatus.CANCELLATION_FAILED) {
				return NextAction.NONE;
			}
			return beforeCheckIn
				? NextAction.RETRY_CANCELLATION
				: NextAction.CONTACT_SUPPORT;
		}

		private static String userFailureCode(
			PaymentOperationStatus status,
			PaymentOperationType operationType
		) {
			return switch (status) {
				case DECLINED -> operationType == PaymentOperationType.CANCEL
					? "PAYMENT_CANCELLATION_DECLINED"
					: "PAYMENT_DECLINED";
				case MANUAL_REVIEW -> "PAYMENT_REVIEW_REQUIRED";
				case QUEUED, EXECUTING, WAITING_RETRY, APPLIED -> null;
			};
		}

		private static Long retryAfterSeconds(PaymentOperation operation, Instant serverTime) {
			return switch (operation.getStatus()) {
				case QUEUED, EXECUTING -> MIN_POLL_INTERVAL_SECONDS;
				case WAITING_RETRY -> secondsUntil(operation.getNextAttemptAt(), serverTime);
				case MANUAL_REVIEW -> MAX_POLL_INTERVAL_SECONDS;
				case APPLIED, DECLINED -> null;
			};
		}

		private static long secondsUntil(Instant nextAttemptAt, Instant serverTime) {
			if (nextAttemptAt == null) {
				return MIN_POLL_INTERVAL_SECONDS;
			}
			Duration remaining = Duration.between(serverTime, nextAttemptAt);
			if (remaining.isNegative() || remaining.isZero()) {
				return MIN_POLL_INTERVAL_SECONDS;
			}
			if (remaining.compareTo(MAX_POLL_INTERVAL) >= 0) {
				return MAX_POLL_INTERVAL_SECONDS;
			}
			long roundedSeconds = remaining.getNano() == 0
				? remaining.getSeconds()
				: Math.addExact(remaining.getSeconds(), 1L);
			return Math.max(MIN_POLL_INTERVAL_SECONDS, roundedSeconds);
		}

		private static String userMessage(
			PaymentOperationStatus status,
			PaymentOperationType operationType,
			NextAction nextAction
		) {
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
