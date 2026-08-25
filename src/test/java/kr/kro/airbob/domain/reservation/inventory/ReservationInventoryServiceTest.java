package kr.kro.airbob.domain.reservation.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryInvariantViolationException;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryNotReadyException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationInventoryService")
class ReservationInventoryServiceTest {

	private static final long ACCOMMODATION_ID = 17L;
	private static final long RESERVATION_ID = 31L;
	private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 10);
	private static final LocalDate CHECK_OUT = LocalDate.of(2026, 9, 12);
	private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

	@Mock private AccommodationInventoryDayRepository repository;

	private ReservationInventoryService service;

	@BeforeEach
	void setUp() {
		service = new ReservationInventoryService(repository);
	}

	@Test
	@DisplayName("complete seed coverage returns after one snapshot without touching existing owners")
	void completeCoverageReturnsWithoutWritesOrASecondRead() {
		List<AccommodationInventoryDay> days = List.of(
			free(CHECK_IN),
			occupied(CHECK_IN.plusDays(1), 99L)
		);
		given(repository.findSnapshot(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(days);

		service.seed(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT);

		then(repository).should()
			.findSnapshot(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT);
		then(repository).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("seed uses READ_COMMITTED so a post-upsert coverage read sees concurrent commits")
	void seedUsesReadCommittedIsolation() throws NoSuchMethodException {
		Method seed = ReservationInventoryService.class.getMethod(
			"seed", Long.class, LocalDate.class, LocalDate.class);

		Transactional transaction = seed.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
	}

	@Test
	@DisplayName("seed inserts only missing dates and verifies exact coverage afterwards")
	void seedsOnlyMissingDates() {
		List<AccommodationInventoryDay> complete = List.of(
			free(CHECK_IN),
			occupied(CHECK_IN.plusDays(1), 99L)
		);
		given(repository.findSnapshot(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT))
			.willReturn(List.of(free(CHECK_IN)), complete);

		service.seed(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT);

		then(repository).should().seedMissingDays(
			ACCOMMODATION_ID, List.of(CHECK_IN.plusDays(1)));
		then(repository).should(times(2))
			.findSnapshot(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT);
	}

	@Test
	@DisplayName("missing calendar coverage is operational not-ready rather than sold inventory")
	void rejectsMissingCoverageAsNotReady() {
		given(repository.findSnapshot(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT))
			.willReturn(List.of(free(CHECK_IN)));

		assertThatThrownBy(() -> service.isRangeAvailableSnapshot(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW))
			.isInstanceOf(ReservationInventoryNotReadyException.class);
	}

	@Test
	@DisplayName("availability ranges도 누락된 calendar coverage를 가용으로 오판하지 않고 not-ready로 거절한다")
	void unavailableRangesRejectMissingCoverageAsNotReady() {
		given(repository.findSnapshot(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT))
			.willReturn(List.of(free(CHECK_IN)));

		assertThatThrownBy(() -> service.findUnavailableRangesSnapshot(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW))
			.isInstanceOf(ReservationInventoryNotReadyException.class);
	}

	@Test
	@DisplayName("snapshot treats FREE and an expired HOLD as available")
	void snapshotTreatsExpiredHoldAsAvailable() {
		given(repository.findSnapshot(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(List.of(
			free(CHECK_IN),
			hold(CHECK_IN.plusDays(1), 99L, NOW)
		));

		assertThat(service.isRangeAvailableSnapshot(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW)).isTrue();
	}

	@Test
	@DisplayName("snapshot reports a live HOLD or OCCUPIED night as unavailable")
	void snapshotReportsOccupancy() {
		given(repository.findSnapshot(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(List.of(
			free(CHECK_IN),
			hold(CHECK_IN.plusDays(1), 99L, NOW.plusSeconds(1))
		));

		assertThat(service.isRangeAvailableSnapshot(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW)).isFalse();
	}

	@Test
	@DisplayName("availability snapshot은 FREE와 exact-cutoff HOLD를 제외하고 live HOLD·OCCUPIED를 연속 불가 구간으로 만든다")
	void snapshotBuildsUnavailableRangesFromInventoryStateAtExactCutoff() {
		LocalDate endExclusive = CHECK_IN.plusDays(6);
		given(repository.findSnapshot(ACCOMMODATION_ID, CHECK_IN, endExclusive)).willReturn(List.of(
			free(CHECK_IN),
			hold(CHECK_IN.plusDays(1), 98L, NOW),
			hold(CHECK_IN.plusDays(2), 99L, NOW.plus(1, ChronoUnit.MICROS)),
			occupied(CHECK_IN.plusDays(3), 100L),
			free(CHECK_IN.plusDays(4)),
			occupied(CHECK_IN.plusDays(5), 101L)
		));

		List<ReservationInventoryService.UnavailableRange> ranges =
			service.findUnavailableRangesSnapshot(
				ACCOMMODATION_ID, CHECK_IN, endExclusive, NOW);

		assertThat(ranges).containsExactly(
			new ReservationInventoryService.UnavailableRange(
				CHECK_IN.plusDays(2), CHECK_IN.plusDays(4)),
			new ReservationInventoryService.UnavailableRange(
				CHECK_IN.plusDays(5), endExclusive)
		);
	}

	@Test
	@DisplayName("NOWAIT claim locks exact ordered coverage and rejects a live owner")
	void lockAvailableRangeRejectsConflict() {
		given(repository.lockRangeNowait(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(List.of(
			free(CHECK_IN),
			occupied(CHECK_IN.plusDays(1), 99L)
		));

		assertThatThrownBy(() -> service.lockAvailableRangeNowait(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW))
			.isInstanceOf(ReservationConflictException.class);
	}

	@Test
	@DisplayName("locked FREE and expired HOLD nights can be atomically claimed for a pending reservation")
	void claimsLockedRangeForPendingReservation() {
		List<AccommodationInventoryDay> days = List.of(
			free(CHECK_IN),
			hold(CHECK_IN.plusDays(1), 99L, NOW)
		);
		given(repository.lockRangeNowait(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(days);
		given(repository.claimAvailableRange(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW,
			RESERVATION_ID, AccommodationInventoryState.HOLD, NOW.plusSeconds(900)))
			.willReturn(2);

		ReservationInventoryService.LockedRange locked = service.lockAvailableRangeNowait(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW);
		service.claimLockedForPending(locked, RESERVATION_ID, NOW.plusSeconds(900));

		then(repository).should().claimAvailableRange(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW,
			RESERVATION_ID, AccommodationInventoryState.HOLD, NOW.plusSeconds(900));
	}

	@Test
	@DisplayName("complimentary checkout claims the locked nights directly as OCCUPIED")
	void claimsLockedRangeForBookedReservation() {
		given(repository.lockRangeNowait(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT))
			.willReturn(List.of(free(CHECK_IN), free(CHECK_IN.plusDays(1))));
		given(repository.claimAvailableRange(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW,
			RESERVATION_ID, AccommodationInventoryState.OCCUPIED, null))
			.willReturn(2);

		var locked = service.lockAvailableRangeNowait(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW);
		service.claimLockedForBooked(locked, RESERVATION_ID);

		then(repository).should().claimAvailableRange(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW,
			RESERVATION_ID, AccommodationInventoryState.OCCUPIED, null);
	}

	@Test
	@DisplayName("a partial claim count is an invariant violation so the caller transaction rolls back")
	void rejectsPartialClaim() {
		given(repository.lockRangeNowait(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT))
			.willReturn(List.of(free(CHECK_IN), free(CHECK_IN.plusDays(1))));
		given(repository.claimAvailableRange(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW,
			RESERVATION_ID, AccommodationInventoryState.HOLD, NOW.plusSeconds(900)))
			.willReturn(1);
		var locked = service.lockAvailableRangeNowait(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, NOW);

		assertThatThrownBy(() -> service.claimLockedForPending(
			locked, RESERVATION_ID, NOW.plusSeconds(900)))
			.isInstanceOf(ReservationInventoryInvariantViolationException.class);
	}

	@Test
	@DisplayName("payment entry uses the blocking lock and transitions the exact HOLD owner to OCCUPIED")
	void transitionsHeldOwnerToOccupied() {
		given(repository.lockRange(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(List.of(
			hold(CHECK_IN, RESERVATION_ID, NOW.plusSeconds(900)),
			hold(CHECK_IN.plusDays(1), RESERVATION_ID, NOW.plusSeconds(900))
		));
		given(repository.transitionExactOwner(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, RESERVATION_ID,
			AccommodationInventoryState.HOLD, AccommodationInventoryState.OCCUPIED))
			.willReturn(2);

		service.transitionHeldToOccupied(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, RESERVATION_ID, NOW);

		then(repository).should().transitionExactOwner(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, RESERVATION_ID,
			AccommodationInventoryState.HOLD, AccommodationInventoryState.OCCUPIED);
	}

	@Test
	@DisplayName("payment entry rejects an inventory HOLD expiring at the exact decision microsecond")
	void transitionRejectsExactExpiryAtMicrosecondPrecision() {
		given(repository.lockRange(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(List.of(
			hold(CHECK_IN, RESERVATION_ID, NOW.plusNanos(999)),
			hold(CHECK_IN.plusDays(1), RESERVATION_ID, NOW.plusSeconds(1))
		));

		assertThatThrownBy(() -> service.transitionHeldToOccupied(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, RESERVATION_ID, NOW))
			.isInstanceOf(ReservationInventoryInvariantViolationException.class);
	}

	@Test
	@DisplayName("releaseOccupied is strict for OCCUPIED inventory and never frees another reservation")
	void occupiedReleaseRequiresExactOwnerAndState() {
		given(repository.lockRange(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(List.of(
			occupied(CHECK_IN, RESERVATION_ID),
			occupied(CHECK_IN.plusDays(1), 99L)
		));

		assertThatThrownBy(() -> service.releaseOccupied(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, RESERVATION_ID))
			.isInstanceOf(ReservationInventoryInvariantViolationException.class);
	}

	@Test
	@DisplayName("expired HOLD release skips dates already reclaimed by a new reservation")
	void heldReleaseIsTakeoverSafe() {
		given(repository.lockRange(ACCOMMODATION_ID, CHECK_IN, CHECK_OUT)).willReturn(List.of(
			hold(CHECK_IN, 99L, NOW.plusSeconds(900)),
			hold(CHECK_IN.plusDays(1), RESERVATION_ID, NOW)
		));
		given(repository.releaseExactOwner(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, RESERVATION_ID,
			AccommodationInventoryState.HOLD)).willReturn(1);

		int released = service.releaseHeldIfOwned(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, RESERVATION_ID);

		assertThat(released).isOne();
		then(repository).should().releaseExactOwner(
			ACCOMMODATION_ID, CHECK_IN, CHECK_OUT, RESERVATION_ID,
			AccommodationInventoryState.HOLD);
	}

	private AccommodationInventoryDay free(LocalDate date) {
		return new AccommodationInventoryDay(
			ACCOMMODATION_ID, date, AccommodationInventoryState.FREE, null, null);
	}

	private AccommodationInventoryDay hold(LocalDate date, Long owner, Instant expiresAt) {
		return new AccommodationInventoryDay(
			ACCOMMODATION_ID, date, AccommodationInventoryState.HOLD, owner, expiresAt);
	}

	private AccommodationInventoryDay occupied(LocalDate date, Long owner) {
		return new AccommodationInventoryDay(
			ACCOMMODATION_ID, date, AccommodationInventoryState.OCCUPIED, owner, null);
	}
}
