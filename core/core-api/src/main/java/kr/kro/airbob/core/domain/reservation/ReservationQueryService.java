package kr.kro.airbob.core.domain.reservation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.reservation.port.ReservationReviewReader;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;
import lombok.RequiredArgsConstructor;

/** 게스트·호스트 예약 목록과 상세 조회. */
@Service
@RequiredArgsConstructor
public class ReservationQueryService {

	private final ReservationReader reservationReader;
	private final ReservationReviewReader reviewReader;
	private final CursorPageInfoCreator cursorPageInfoCreator;
	private final Clock clock;

	@Transactional(readOnly = true)
	public GuestReservationPage findMyReservations(Long memberId,
		CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {
		Instant now = clock.instant();

		Slice<GuestReservationListProjection> reservationSlice = reservationReader.readGuestPage(
			memberId, cursorRequest, filterType, now);

		List<GuestReservationPage.Item> reservations = reservationSlice.getContent().stream()
			.map(reservation -> GuestReservationPage.Item.of(reservation, now))
			.toList();

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reservationSlice.getContent(),
			reservationSlice.hasNext(),
			GuestReservationListProjection::id,
			GuestReservationListProjection::createdAt
		);

		return new GuestReservationPage(reservations, pageInfo);
	}

	@Transactional(readOnly = true)
	public GuestReservationDetail findMyReservationDetail(String reservationUidStr, Long memberId) {
		UUID reservationUid = parseReservationUid(reservationUidStr);

		GuestReservationDetailProjection reservation = reservationReader.readGuestDetail(reservationUid, memberId);

		Instant serverTime = clock.instant();
		boolean canWriteReview = canWriteReview(memberId, reservation, serverTime);

		return GuestReservationDetail.of(reservation, canWriteReview, serverTime);
	}

	@Transactional(readOnly = true)
	public HostReservationPage findHostReservations(Long hostId, CursorRequest.CursorPageRequest cursorRequest,
		ReservationFilterType filterType) {
		Slice<HostReservationListProjection> reservationSlice = reservationReader.readHostPage(
			hostId, cursorRequest, filterType, clock.instant());

		List<HostReservationListProjection> reservations = reservationSlice.getContent();

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			reservations,
			reservationSlice.hasNext(),
			HostReservationListProjection::id,
			HostReservationListProjection::createdAt
		);

		return new HostReservationPage(reservations, pageInfo);
	}

	@Transactional(readOnly = true)
	public HostReservationDetailProjection findHostReservationDetail(String reservationUidStr, Long hostId) {
		UUID reservationUid = parseReservationUid(reservationUidStr);
		return reservationReader.readHostDetail(reservationUid, hostId);
	}

	private UUID parseReservationUid(String reservationUid) {
		try {
			return UUID.fromString(reservationUid);
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw new ReservationNotFoundException();
		}
	}

	private boolean canWriteReview(Long memberId, GuestReservationDetailProjection reservation, Instant serverTime) {
		if (reservation.accommodationStatus() != AccommodationStatus.PUBLISHED
			|| !reservation.status().isReviewableReservation()
			|| reservation.checkOutAt().isAfter(serverTime)) {
			return false;
		}
		return !reviewReader.hasPublishedReview(reservation.accommodationId(), memberId);
	}
}
