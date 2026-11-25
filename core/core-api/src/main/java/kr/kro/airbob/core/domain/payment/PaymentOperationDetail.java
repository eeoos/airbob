package kr.kro.airbob.core.domain.payment;

import java.time.Duration;
import java.time.Instant;

import kr.kro.airbob.core.domain.reservation.Reservation;
import kr.kro.airbob.core.domain.reservation.ReservationStatus;

/**
 * 회원이 조회한 결제 operation. 회원의 다음 행동과 재조회 간격은 serverTime 기준으로 계산한다.
 * retryAfterSeconds는 진행 중이면 2~30초, 수동 확인이면 30초, 끝난 작업이면 null이다.
 */
public record PaymentOperationDetail(
	PaymentOperationDetailRow operation,
	NextAction nextAction,
	Long retryAfterSeconds,
	Instant serverTime) {

	private static final long MIN_POLL_INTERVAL_SECONDS = 2L;
	private static final long MAX_POLL_INTERVAL_SECONDS = 30L;
	private static final Duration MAX_POLL_INTERVAL = Duration.ofSeconds(MAX_POLL_INTERVAL_SECONDS);

	/** 회원이 다음에 할 일. 워커의 다음 실행 단계(`PaymentOperationNextAction`)와 다르다. */
	public enum NextAction {
		POLL,
		START_NEW_CHECKOUT,
		RETRY_CANCELLATION,
		CONTACT_SUPPORT,
		NONE
	}

	public static PaymentOperationDetail of(PaymentOperationDetailRow operation, Instant serverTime) {
		return new PaymentOperationDetail(
			operation,
			nextAction(operation, serverTime),
			retryAfterSeconds(operation, serverTime),
			serverTime);
	}

	private static NextAction nextAction(PaymentOperationDetailRow operation, Instant serverTime) {
		return switch (operation.status()) {
			case QUEUED, EXECUTING, WAITING_RETRY -> NextAction.POLL;
			case APPLIED -> NextAction.NONE;
			case DECLINED -> declinedNextAction(operation, serverTime);
			case MANUAL_REVIEW -> NextAction.CONTACT_SUPPORT;
		};
	}

	private static NextAction declinedNextAction(
		PaymentOperationDetailRow operation,
		Instant serverTime) {
		boolean beforeCheckIn = operation.checkInAt() != null
			&& serverTime.isBefore(operation.checkInAt());
		if (operation.operationType() == PaymentOperationType.CONFIRM) {
			ReservationStatus effectiveStatus = Reservation.effectiveStatus(
				operation.reservationStatus(), operation.expiresAt(), serverTime);
			return effectiveStatus == ReservationStatus.EXPIRED && beforeCheckIn
				? NextAction.START_NEW_CHECKOUT
				: NextAction.NONE;
		}
		if (operation.reservationStatus() != ReservationStatus.CANCELLATION_FAILED) {
			return NextAction.NONE;
		}
		return beforeCheckIn
			? NextAction.RETRY_CANCELLATION
			: NextAction.CONTACT_SUPPORT;
	}

	private static Long retryAfterSeconds(PaymentOperationDetailRow operation, Instant serverTime) {
		return switch (operation.status()) {
			case QUEUED, EXECUTING -> MIN_POLL_INTERVAL_SECONDS;
			case WAITING_RETRY -> secondsUntil(operation.nextAttemptAt(), serverTime);
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
}
