package kr.kro.airbob.domain.reservation.repository.impl;

import static kr.kro.airbob.domain.reservation.entity.QReservation.*;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.*;

import java.time.LocalDateTime;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.ReservationRepositoryCustom;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReservationRepositoryImpl implements ReservationRepositoryCustom {

	private final JPAQueryFactory queryFactory;

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
	public List<Reservation> findFutureCompletedReservations(Long accommodationId) {
		return queryFactory
			.selectFrom(reservation)
			.where(
				reservation.accommodation.id.eq(accommodationId),
				reservation.status.eq(ReservationStatus.CONFIRMED),
				reservation.checkOut.goe(LocalDateTime.now()) // 체크아웃 날짜가 오늘 이후인 것
			)
			.fetch();
	}
}


