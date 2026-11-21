package kr.kro.airbob.domain.reservation.inventory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryInvariantViolationException;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryNotReadyException;

@Service
public class ReservationInventoryService {

	private final AccommodationInventoryDayRepository repository;

	public ReservationInventoryService(AccommodationInventoryDayRepository repository) {
		this.repository = repository;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void seed(Long accommodationId, LocalDate startInclusive, LocalDate endExclusive) {
		Range range = requireRange(accommodationId, startInclusive, endExclusive);
		List<AccommodationInventoryDay> before = repository.findSnapshot(
			accommodationId, startInclusive, endExclusive);
		List<LocalDate> missingDays = findMissingDays(range, before);
		if (missingDays.isEmpty()) {
			return;
		}
		repository.seedMissingDays(accommodationId, missingDays);
		validateCoverage(
			range,
			repository.findSnapshot(accommodationId, startInclusive, endExclusive),
			false
		);
	}

	@Transactional(readOnly = true)
	public boolean isRangeAvailableSnapshot(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Instant decisionAt
	) {
		Range range = requireRange(accommodationId, startInclusive, endExclusive);
		Instant checkedAt = normalize(decisionAt, "decisionAt");
		List<AccommodationInventoryDay> days = repository.findSnapshot(
			accommodationId, startInclusive, endExclusive);
		validateCoverage(range, days, false);
		return days.stream().allMatch(day -> day.isAvailableAt(checkedAt));
	}

	@Transactional(readOnly = true)
	public List<UnavailableRange> findUnavailableRangesSnapshot(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Instant decisionAt
	) {
		Range range = requireRange(accommodationId, startInclusive, endExclusive);
		Instant checkedAt = normalize(decisionAt, "decisionAt");
		List<AccommodationInventoryDay> days = repository.findSnapshot(
			accommodationId, startInclusive, endExclusive);
		validateCoverage(range, days, false);

		List<UnavailableRange> unavailableRanges = new ArrayList<>();
		LocalDate unavailableStart = null;
		for (AccommodationInventoryDay day : days) {
			if (!day.isAvailableAt(checkedAt)) {
				if (unavailableStart == null) {
					unavailableStart = day.stayDate();
				}
				continue;
			}
			if (unavailableStart != null) {
				unavailableRanges.add(new UnavailableRange(unavailableStart, day.stayDate()));
				unavailableStart = null;
			}
		}
		if (unavailableStart != null) {
			unavailableRanges.add(new UnavailableRange(unavailableStart, endExclusive));
		}
		return List.copyOf(unavailableRanges);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public LockedRange lockAvailableRangeNowait(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Instant decisionAt
	) {
		Range range = requireRange(accommodationId, startInclusive, endExclusive);
		Instant checkedAt = normalize(decisionAt, "decisionAt");
		List<AccommodationInventoryDay> days = repository.lockRangeNowait(
			accommodationId, startInclusive, endExclusive);
		validateCoverage(range, days, false);
		if (days.stream().anyMatch(day -> !day.isAvailableAt(checkedAt))) {
			throw new ReservationConflictException();
		}
		return new LockedRange(range, checkedAt);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void claimLockedForPending(
		LockedRange lockedRange,
		Long reservationId,
		Instant holdExpiresAt
	) {
		LockedRange locked = Objects.requireNonNull(lockedRange, "lockedRange must not be null");
		Long owner = requirePositive(reservationId, "reservationId");
		Instant expiresAt = normalize(holdExpiresAt, "holdExpiresAt");
		if (!expiresAt.isAfter(locked.decisionAt())) {
			throw new IllegalArgumentException("holdExpiresAt must be after the inventory decision");
		}
		claim(locked, owner, AccommodationInventoryState.HOLD, expiresAt);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void claimLockedForBooked(LockedRange lockedRange, Long reservationId) {
		claim(
			Objects.requireNonNull(lockedRange, "lockedRange must not be null"),
			requirePositive(reservationId, "reservationId"),
			AccommodationInventoryState.OCCUPIED,
			null
		);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void transitionHeldToOccupied(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Long reservationId,
		Instant decisionAt
	) {
		Range range = requireRange(accommodationId, startInclusive, endExclusive);
		Long owner = requirePositive(reservationId, "reservationId");
		Instant checkedAt = normalize(decisionAt, "decisionAt");
		List<AccommodationInventoryDay> days = repository.lockRange(
			accommodationId, startInclusive, endExclusive);
		validateCoverage(range, days, true);
		validateExactOwner(days, owner, AccommodationInventoryState.HOLD);
		if (days.stream().anyMatch(day -> !day.holdExpiresAt().isAfter(checkedAt))) {
			throw new ReservationInventoryInvariantViolationException(
				"inventory HOLD expired before payment entry");
		}
		int updated = repository.transitionExactOwner(
			accommodationId,
			startInclusive,
			endExclusive,
			owner,
			AccommodationInventoryState.HOLD,
			AccommodationInventoryState.OCCUPIED
		);
		validateUpdatedCount(range, updated, "HOLD to OCCUPIED transition");
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void verifyOccupied(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Long reservationId
	) {
		Range range = requireRange(accommodationId, startInclusive, endExclusive);
		Long owner = requirePositive(reservationId, "reservationId");
		List<AccommodationInventoryDay> days = repository.lockRange(
			accommodationId, startInclusive, endExclusive);
		validateCoverage(range, days, true);
		validateExactOwner(days, owner, AccommodationInventoryState.OCCUPIED);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void releaseOccupied(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Long reservationId
	) {
		Range range = requireRange(accommodationId, startInclusive, endExclusive);
		Long owner = requirePositive(reservationId, "reservationId");
		List<AccommodationInventoryDay> days = repository.lockRange(
			accommodationId, startInclusive, endExclusive);
		validateCoverage(range, days, true);
		validateExactOwner(days, owner, AccommodationInventoryState.OCCUPIED);
		int updated = repository.releaseExactOwner(
			accommodationId,
			startInclusive,
			endExclusive,
			owner,
			AccommodationInventoryState.OCCUPIED
		);
		validateUpdatedCount(range, updated, "inventory release");
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public int releaseHeldIfOwned(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		Long reservationId
	) {
		Range range = requireRange(accommodationId, startInclusive, endExclusive);
		Long owner = requirePositive(reservationId, "reservationId");
		List<AccommodationInventoryDay> days = repository.lockRange(
			accommodationId, startInclusive, endExclusive);
		validateCoverage(range, days, true);
		long stillOwned = days.stream().filter(day -> day.isOwnedBy(owner)).count();
		if (days.stream().anyMatch(day -> day.isOwnedBy(owner)
			&& day.state() != AccommodationInventoryState.HOLD)) {
			throw new ReservationInventoryInvariantViolationException(
				"reservation still owns inventory in a non-HOLD state");
		}
		int released = repository.releaseExactOwner(
			accommodationId,
			startInclusive,
			endExclusive,
			owner,
			AccommodationInventoryState.HOLD
		);
		if (released != stillOwned) {
			throw new ReservationInventoryInvariantViolationException(
				"takeover-safe HOLD release updated " + released + " of " + stillOwned
					+ " still-owned nights");
		}
		return released;
	}

	private void claim(
		LockedRange locked,
		Long reservationId,
		AccommodationInventoryState targetState,
		Instant holdExpiresAt
	) {
		Range range = locked.range();
		int updated = repository.claimAvailableRange(
			range.accommodationId(),
			range.startInclusive(),
			range.endExclusive(),
			locked.decisionAt(),
			reservationId,
			targetState,
			holdExpiresAt
		);
		validateUpdatedCount(range, updated, "inventory claim");
	}

	private void validateCoverage(
		Range range,
		List<AccommodationInventoryDay> days,
		boolean invariantOnFailure
	) {
		List<AccommodationInventoryDay> snapshot = List.copyOf(days);
		boolean exact = snapshot.size() == range.nightCount();
		for (int index = 0; exact && index < snapshot.size(); index++) {
			AccommodationInventoryDay day = snapshot.get(index);
			exact = Objects.equals(day.accommodationId(), range.accommodationId())
				&& day.stayDate().equals(range.startInclusive().plusDays(index));
		}
		if (exact) {
			return;
		}
		if (invariantOnFailure) {
			throw new ReservationInventoryInvariantViolationException(
				"inventory calendar coverage changed for an existing reservation");
		}
		throw new ReservationInventoryNotReadyException();
	}

	private List<LocalDate> findMissingDays(
		Range range,
		List<AccommodationInventoryDay> existingDays
	) {
		boolean[] present = new boolean[range.nightCount()];
		for (AccommodationInventoryDay day : existingDays) {
			if (!Objects.equals(day.accommodationId(), range.accommodationId())
				|| day.stayDate().isBefore(range.startInclusive())
				|| !day.stayDate().isBefore(range.endExclusive())) {
				throw new ReservationInventoryInvariantViolationException(
					"inventory seed snapshot contains a row outside the requested range");
			}
			int offset = Math.toIntExact(ChronoUnit.DAYS.between(
				range.startInclusive(), day.stayDate()));
			if (present[offset]) {
				throw new ReservationInventoryInvariantViolationException(
					"inventory seed snapshot contains a duplicate stay date");
			}
			present[offset] = true;
		}

		List<LocalDate> missingDays = new ArrayList<>();
		for (int offset = 0; offset < present.length; offset++) {
			if (!present[offset]) {
				missingDays.add(range.startInclusive().plusDays(offset));
			}
		}
		return List.copyOf(missingDays);
	}

	private void validateExactOwner(
		List<AccommodationInventoryDay> days,
		Long reservationId,
		AccommodationInventoryState expectedState
	) {
		if (days.stream().anyMatch(day -> day.state() != expectedState
			|| !day.isOwnedBy(reservationId))) {
			throw new ReservationInventoryInvariantViolationException(
				"inventory range does not match the expected reservation owner and state");
		}
	}

	private void validateUpdatedCount(Range range, int updated, String operation) {
		if (updated != range.nightCount()) {
			throw new ReservationInventoryInvariantViolationException(
				operation + " updated " + updated + " of " + range.nightCount() + " nights");
		}
	}

	private Range requireRange(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive
	) {
		Long id = requirePositive(accommodationId, "accommodationId");
		Objects.requireNonNull(startInclusive, "startInclusive must not be null");
		Objects.requireNonNull(endExclusive, "endExclusive must not be null");
		long nights = ChronoUnit.DAYS.between(startInclusive, endExclusive);
		if (nights <= 0 || nights > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("inventory range must contain at least one night");
		}
		return new Range(id, startInclusive, endExclusive, Math.toIntExact(nights));
	}

	private Long requirePositive(Long value, String name) {
		if (value == null || value <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}

	private Instant normalize(Instant value, String name) {
		return Objects.requireNonNull(value, name + " must not be null")
			.truncatedTo(ChronoUnit.MICROS);
	}

	public static final class LockedRange {
		private final Range range;
		private final Instant decisionAt;

		private LockedRange(Range range, Instant decisionAt) {
			this.range = Objects.requireNonNull(range, "range must not be null");
			this.decisionAt = Objects.requireNonNull(decisionAt, "decisionAt must not be null");
		}

		private Range range() {
			return range;
		}

		private Instant decisionAt() {
			return decisionAt;
		}
	}

	private record Range(
		Long accommodationId,
		LocalDate startInclusive,
		LocalDate endExclusive,
		int nightCount
	) {
	}

	public record UnavailableRange(
		LocalDate startInclusive,
		LocalDate endExclusive
	) {
		public UnavailableRange {
			Objects.requireNonNull(startInclusive, "startInclusive must not be null");
			Objects.requireNonNull(endExclusive, "endExclusive must not be null");
			if (!endExclusive.isAfter(startInclusive)) {
				throw new IllegalArgumentException(
					"unavailable inventory range must contain at least one night");
			}
		}
	}
}
