package kr.kro.airbob.domain.reservation.repository.impl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QAddress.*;
import static kr.kro.airbob.domain.member.entity.QMember.*;
import static kr.kro.airbob.domain.reservation.entity.QReservation.reservation;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.member.entity.QMember;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.ReservationRepositoryCustom;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReservationRepositoryImpl implements ReservationRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	private final QMember guestMember = new QMember("guestMember");

	@Override
	public boolean existsConflictingReservation(Long accommodationId, LocalDateTime checkIn, LocalDateTime checkOut) {
		Integer fetchFirst = queryFactory
			.selectOne()
			.from(reservation)
			.where(
				reservation.accommodation.id.eq(accommodationId),
				reservation.status.in(CONFIRMED, PAYMENT_PENDING),
				reservation.checkIn.lt(checkOut),
				reservation.checkOut.gt(checkIn)
			)
			.fetchFirst();

		return fetchFirst != null;
	}

	@Override
	public boolean existsCompletedReservationByGuest(Long accommodationId, Long memberId) {
		Integer fetchFirst = queryFactory
			.selectOne()
			.from(reservation)
			.where(
				reservation.accommodation.id.eq(accommodationId),
				reservation.guest.id.eq(memberId),
				reservation.status.eq(ReservationStatus.CONFIRMED)
			)
			.fetchFirst();
		return fetchFirst != null;
	}

	@Override
	public boolean existsPastCompletedReservationByGuest(Long accommodationId, Long memberId) {
		Integer fetchFirst = queryFactory
			.selectOne()
			.from(reservation)
			.where(
				reservation.accommodation.id.eq(accommodationId),
				reservation.guest.id.eq(memberId),
				reservation.status.eq(CONFIRMED),
				reservation.checkOut.before(LocalDateTime.now())
			)
			.fetchFirst();
		return fetchFirst != null;
	}

	@Override
	public List<Reservation> findFutureCompletedReservations(UUID accommodationUid) {
		return queryFactory
			.selectFrom(reservation)
			.where(
				reservation.accommodation.accommodationUid.eq(accommodationUid),
				reservation.status.eq(ReservationStatus.CONFIRMED),
				reservation.checkOut.goe(LocalDateTime.now()) // 체크아웃 날짜가 오늘 이후인 것
			)
			.fetch();
	}

	@Override
	public Slice<Reservation> findMyReservationsByGuestIdWithCursor(Long guestId, Long lastId,
		LocalDateTime lastCreatedAt, ReservationFilterType filterType, Pageable pageable) {

		List<Reservation> content = queryFactory
			.selectFrom(reservation)
			.leftJoin(reservation.accommodation, accommodation).fetchJoin()
			.leftJoin(accommodation.address, address).fetchJoin()
			.where(
				reservation.guest.id.eq(guestId),
				buildGuestReservationFilter(filterType),
				cursorCondition(lastId, lastCreatedAt)
			)
			.orderBy(reservation.createdAt.desc(), reservation.id.desc())
			.limit(pageable.getPageSize() + 1)
			.fetch();

		boolean hasNext = content.size() > pageable.getPageSize();
		if (hasNext) {
			content.remove(pageable.getPageSize());
		}

		return new SliceImpl<>(content, pageable, hasNext);
	}

	private BooleanExpression cursorCondition(Long lastId, LocalDateTime lastCreatedAt) {
		if (lastId == null || lastCreatedAt == null) {
			return null;
		}

		return reservation.createdAt.lt(lastCreatedAt)
			.or(reservation.createdAt.eq(lastCreatedAt)
				.and(reservation.id.lt(lastId)));
	}

	@Override
	public Optional<Reservation> findReservationDetailByUidAndGuestId(UUID reservationUid, Long guestId) {

		Reservation result = queryFactory
			.selectFrom(reservation)
			.leftJoin(reservation.accommodation, accommodation).fetchJoin()
			.leftJoin(accommodation.address, address).fetchJoin()
			.leftJoin(accommodation.member, member).fetchJoin()
			.where(
				reservation.reservationUid.eq(reservationUid),
				reservation.guest.id.eq(guestId)
			)
			.fetchOne();

		return Optional.ofNullable(result);
	}

	@Override
	public Slice<Reservation> findHostReservationsByHostIdWithCursor(Long hostId, Long lastId,
		LocalDateTime lastCreatedAt, ReservationFilterType filterType, Pageable pageable) {

		List<Reservation> content = queryFactory
			.selectFrom(reservation)
			.innerJoin(reservation.accommodation, accommodation).fetchJoin()
			.innerJoin(reservation.guest, guestMember).fetchJoin()
			.where(
				accommodation.member.id.eq(hostId),
				reservation.status.ne(PAYMENT_PENDING),
				buildHostReservationFilter(filterType),
				cursorCondition(lastId, lastCreatedAt)
			)
			.orderBy(reservation.createdAt.desc(), reservation.id.desc())
			.limit(pageable.getPageSize() + 1)
			.fetch();

		boolean hasNext = content.size() > pageable.getPageSize();
		if (hasNext) {
			content.remove(pageable.getPageSize());
		}

		return new SliceImpl<>(content, pageable, hasNext);
	}

	@Override
	public Optional<Reservation> findHostReservationDetailByUidAndHostId(UUID reservationUid, Long hostId) {
		Reservation result = queryFactory
			.selectFrom(reservation)
			.innerJoin(reservation.accommodation, accommodation).fetchJoin()
			.innerJoin(reservation.guest, guestMember).fetchJoin()
			.where(
				reservation.reservationUid.eq(reservationUid),
				accommodation.member.id.eq(hostId)
			)
			.fetchOne();

		return Optional.ofNullable(result);
	}

	private BooleanExpression buildGuestReservationFilter(ReservationFilterType filterType) {
		LocalDateTime now = LocalDateTime.now();

		switch (filterType) {
			case PAST:
				// 이전 여행: CONFIRMED이면서 체크아웃이 과거
				return reservation.status.eq(CONFIRMED)
					.and(reservation.checkOut.before(now));
			case CANCELLED:
				// 취소된: CANCELLED, CANCELLATION_FAILED, EXPIRED
				return reservation.status.in(CANCELLED, CANCELLATION_FAILED, EXPIRED);
			case UPCOMING:
				// 다가올 여행: PENDING이거나, CONFIRMED이면서 체크아웃이 미래
				return reservation.status.in(PAYMENT_PENDING)
					.or(
						reservation.status.eq(CONFIRMED).and(reservation.checkOut.after(now))
					);
			default:
				return null;
		}
	}

	private BooleanExpression buildHostReservationFilter(ReservationFilterType filterType) {
		LocalDateTime now = LocalDateTime.now();
		switch (filterType) {
			case CANCELLED:
				// 취소된 예약: CANCELLED, CANCELLATION_FAILED, EXPIRED
				return reservation.status.in(CANCELLED, CANCELLATION_FAILED, EXPIRED);
			case PAST:
				// 완료된: CONFIRMED이면서 체크아웃이 과거
				return reservation.status.eq(CONFIRMED).and(reservation.checkOut.before(now));
			case UPCOMING:
				// 다가오는 예약: CONFIRMED이면서 체크아웃이 미래
				return reservation.status.eq(CONFIRMED).and(reservation.checkOut.after(now));
			default:
				return null;
		}
	}
}


