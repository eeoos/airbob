package kr.kro.airbob.core.domain.reservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.port.AccommodationReservationReader;
import kr.kro.airbob.core.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import lombok.RequiredArgsConstructor;

/** 예약 조회 구현 컴포넌트. 다른 도메인은 ReservationRepository 대신 이 컴포넌트로 읽는다. */
@Component
@RequiredArgsConstructor
public class ReservationReader implements AccommodationReservationReader {

	private final ReservationRepository reservationRepository;

	/** 예약 행을 FOR UPDATE로 잠근다. 예약 → 날짜 행 잠금 순서의 첫 단계다. */
	public Reservation readForUpdate(Long reservationId) {
		return reservationRepository.findByIdWithLock(reservationId).orElseThrow(ReservationNotFoundException::new);
	}

	/** 예약 UID로 예약 행을 FOR UPDATE로 잠근다. */
	public Reservation readByUidForUpdate(UUID reservationUid) {
		return reservationRepository.findByReservationUidWithLock(reservationUid)
			.orElseThrow(ReservationNotFoundException::new);
	}

	/** 이미 끝난 멱등 체크아웃 요청이 만든 예약을 다시 읽는다. 게스트가 다르면 상태가 바뀐 것으로 본다. */
	public Reservation readCheckoutReplay(Long reservationId, Long guestId) {
		return reservationRepository.findCheckoutReplayByIdAndGuestId(reservationId, guestId)
			.orElseThrow(ReservationStateChangeException::new);
	}

	/** 게스트 본인의 예약을 예약 UID로 FOR UPDATE 잠근다. 없거나 다른 게스트의 예약이면 없는 것으로 본다. */
	public Reservation readGuestOwnedForUpdate(UUID reservationUid, Long guestId) {
		return reservationRepository.findByReservationUidAndGuestIdWithLock(reservationUid, guestId)
			.orElseThrow(ReservationNotFoundException::new);
	}

	/** 만료 시각이 cutoff 이전인 결제 대기 예약을 정리용으로 잠가 최대 batchSize건 읽는다. */
	public List<Reservation> readExpiredPendingBatchForCleanup(Instant cutoff, int batchSize) {
		return reservationRepository.findExpiredPendingBatchForCleanup(cutoff, batchSize);
	}

	/** 색인 기간 안에서 숙소의 활성 예약 날짜 구간. 잠그지 않는 조회다. */
	public List<ReservationDateRange> readActiveRanges(
		Long accommodationId, LocalDate windowStartInclusive, LocalDate windowEndExclusive) {
		return reservationRepository.findActiveReservationRangesByAccommodationId(
			accommodationId, windowStartInclusive, windowEndExclusive);
	}

	/** 회원이 이 숙소에 묵고 체크아웃까지 마친 예약이 있는지. 잠그지 않는 조회다. */
	public boolean existsPastCompletedReservationByGuest(Long accommodationId, Long memberId, Instant now) {
		return reservationRepository.existsPastCompletedReservationByGuest(accommodationId, memberId, now);
	}

	@Override
	public boolean existsFutureInventoryReservation(Long accommodationId, Instant now) {
		return reservationRepository.existsFutureInventoryReservation(accommodationId, now);
	}

	/** 게스트 예약 목록. now는 결제 대기 만료와 필터(예정·지난 예약) 판단 기준이다. */
	public Slice<GuestReservationListProjection> readGuestPage(
		Long guestId, CursorRequest.CursorPageRequest cursor, ReservationFilterType filterType, Instant now) {
		return reservationRepository.findMyReservationsByGuestIdWithCursor(
			guestId, cursor.lastId(), cursor.lastCreatedAt(), filterType, now, PageRequest.of(0, cursor.size()));
	}

	public GuestReservationDetailProjection readGuestDetail(UUID reservationUid, Long guestId) {
		return reservationRepository.findReservationDetailByUidAndGuestId(reservationUid, guestId)
			.orElseThrow(ReservationNotFoundException::new);
	}

	public Slice<HostReservationListProjection> readHostPage(
		Long hostId, CursorRequest.CursorPageRequest cursor, ReservationFilterType filterType, Instant now) {
		return reservationRepository.findHostReservationsByHostIdWithCursor(
			hostId, cursor.lastId(), cursor.lastCreatedAt(), filterType, now, PageRequest.of(0, cursor.size()));
	}

	public HostReservationDetailProjection readHostDetail(UUID reservationUid, Long hostId) {
		return reservationRepository.findHostReservationDetailByUidAndHostId(reservationUid, hostId)
			.orElseThrow(ReservationNotFoundException::new);
	}
}
