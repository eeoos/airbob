package kr.kro.airbob.domain.reservation.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record AccommodationInventoryDay(
	Long accommodationId,
	LocalDate stayDate,
	AccommodationInventoryState state,
	Long reservationId,
	Instant holdExpiresAt
) {

	public AccommodationInventoryDay {
		if (accommodationId == null || accommodationId <= 0) {
			throw new IllegalArgumentException("accommodationId must be positive");
		}
		Objects.requireNonNull(stayDate, "stayDate must not be null");
		Objects.requireNonNull(state, "state must not be null");
		if (holdExpiresAt != null) {
			holdExpiresAt = holdExpiresAt.truncatedTo(ChronoUnit.MICROS);
		}
		validateOwnerTuple(state, reservationId, holdExpiresAt);
	}

	public boolean isAvailableAt(Instant decisionAt) {
		Instant normalizedDecision = Objects.requireNonNull(
			decisionAt, "decisionAt must not be null").truncatedTo(ChronoUnit.MICROS);
		return state == AccommodationInventoryState.FREE
			|| state == AccommodationInventoryState.HOLD
			&& !holdExpiresAt.isAfter(normalizedDecision);
	}

	public boolean isOwnedBy(Long expectedReservationId) {
		return Objects.equals(reservationId, expectedReservationId);
	}

	private static void validateOwnerTuple(
		AccommodationInventoryState state,
		Long reservationId,
		Instant holdExpiresAt
	) {
		switch (state) {
			case FREE -> {
				if (reservationId != null || holdExpiresAt != null) {
					throw new IllegalArgumentException("FREE inventory must not have an owner or expiry");
				}
			}
			case HOLD -> {
				if (reservationId == null || reservationId <= 0 || holdExpiresAt == null) {
					throw new IllegalArgumentException("HOLD inventory requires an owner and expiry");
				}
			}
			case OCCUPIED -> {
				if (reservationId == null || reservationId <= 0 || holdExpiresAt != null) {
					throw new IllegalArgumentException("OCCUPIED inventory requires only an owner");
				}
			}
		}
	}
}
