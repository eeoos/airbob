package kr.kro.airbob.domain.payment.repository.projection;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

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
	Instant expiresAt
) {
}
