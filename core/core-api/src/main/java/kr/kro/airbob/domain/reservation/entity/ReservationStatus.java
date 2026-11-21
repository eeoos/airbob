package kr.kro.airbob.domain.reservation.entity;

import java.util.Set;

public enum ReservationStatus {

	PAYMENT_PENDING,
	PAYMENT_PROCESSING,
	CONFIRMED,
	CANCELLATION_PENDING,
	CANCELLED,
	CANCELLATION_FAILED,
	EXPIRED;

	private static final Set<ReservationStatus> REVIEWABLE_STATUSES = Set.of(
		CONFIRMED,
		CANCELLATION_FAILED
	);

	public boolean isReviewableReservation() {
		return REVIEWABLE_STATUSES.contains(this);
	}

	public static Set<ReservationStatus> reviewableStatuses() {
		return REVIEWABLE_STATUSES;
	}

	public boolean hasConfirmedPayment() {
		return this == CONFIRMED
			|| this == CANCELLATION_PENDING
			|| this == CANCELLATION_FAILED
			|| this == CANCELLED;
	}

}
