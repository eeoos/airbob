package kr.kro.airbob.domain.reservation.repository.impl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QAddress.*;
import static kr.kro.airbob.domain.member.entity.QMember.*;
import static kr.kro.airbob.domain.payment.entity.QPayment.*;
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
		LocalDateTime lastCreatedAt, Pageable pageable) {

		List<Reservation> content = queryFactory
			.selectFrom(reservation)
			.leftJoin(reservation.accommodation, accommodation).fetchJoin()
			.leftJoin(accommodation.address, address).fetchJoin()
			.where(
				reservation.guest.id.eq(guestId),
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
		LocalDateTime lastCreatedAt, Pageable pageable) {

		List<Reservation> content = queryFactory
			.selectFrom(reservation)
			.innerJoin(reservation.accommodation, accommodation).fetchJoin()
			.innerJoin(reservation.guest, guestMember).fetchJoin()
			.where(
				accommodation.member.id.eq(hostId),
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
}


