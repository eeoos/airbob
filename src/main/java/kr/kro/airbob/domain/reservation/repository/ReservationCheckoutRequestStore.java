package kr.kro.airbob.domain.reservation.repository;

import java.time.Instant;

import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutEndpoint;
import kr.kro.airbob.domain.reservation.idempotency.ReservationCheckoutIdentity;

public interface ReservationCheckoutRequestStore {
	ReservationCheckoutRequestClaim lockOrCreate(
		long memberId,
		ReservationCheckoutEndpoint endpoint,
		ReservationCheckoutIdentity identity,
		Instant createdAt
	);

	void complete(long checkoutRequestId, long reservationId, Instant completedAt);
}
