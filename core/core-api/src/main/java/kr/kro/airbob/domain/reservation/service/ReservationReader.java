package kr.kro.airbob.domain.reservation.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.port.AccommodationReservationReader;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.reservation.GuestReservationDetailProjection;
import kr.kro.airbob.domain.reservation.GuestReservationListProjection;
import kr.kro.airbob.domain.reservation.HostReservationDetailProjection;
import kr.kro.airbob.domain.reservation.HostReservationListProjection;
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
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
