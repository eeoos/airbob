package kr.kro.airbob.core.domain.payment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import kr.kro.airbob.core.domain.reservation.ReservationStatus;

public record PaymentOperationDetailRow(
	UUID operationUid,
	Long requesterMemberId,
	PaymentOperationType operationType,
	PaymentOperationStatus status,
	String failureCode,
	LocalDateTime updatedAt,
	Instant nextAttemptAt,
	UUID reservationUid,
	ReservationStatus reservationStatus,
	Instant checkInAt,
	Instant expiresAt) {
}
