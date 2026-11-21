package kr.kro.airbob.domain.reservation.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;

public class AccommodationInventorySeedPolicy {

	private final BookingWindowProvider bookingWindowProvider;
	private final int safetyBufferDays;

	public AccommodationInventorySeedPolicy(
		BookingWindowProvider bookingWindowProvider,
		int safetyBufferDays
	) {
		this.bookingWindowProvider = Objects.requireNonNull(bookingWindowProvider);
		if (safetyBufferDays < 0) {
			throw new IllegalArgumentException("inventory seed safety buffer must not be negative");
		}
		this.safetyBufferDays = safetyBufferDays;
	}

	public SeedRange currentRange(String timeZoneId, Instant decisionAt) {
		if (timeZoneId == null || timeZoneId.isBlank()) {
			throw new IllegalArgumentException("inventory seed timeZoneId must not be blank");
		}
		BookingWindow bookingWindow = bookingWindowProvider.currentFor(
			timeZoneId, Objects.requireNonNull(decisionAt));
		return new SeedRange(
			bookingWindow.startInclusive(),
			bookingWindow.endExclusive().plusDays(safetyBufferDays)
		);
	}

	public record SeedRange(LocalDate startInclusive, LocalDate endExclusive) {
		public SeedRange {
			Objects.requireNonNull(startInclusive);
			Objects.requireNonNull(endExclusive);
			if (!endExclusive.isAfter(startInclusive)) {
				throw new IllegalArgumentException("inventory seed range must contain at least one night");
			}
		}
	}
}
