package kr.kro.airbob.domain.reservation.repository.impl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QAddress.*;
import static kr.kro.airbob.domain.member.entity.QMember.*;
import static kr.kro.airbob.domain.reservation.entity.QReservation.reservation;
import static kr.kro.airbob.domain.reservation.entity.ReservationStatus.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.LockModeType;
import kr.kro.airbob.domain.member.entity.QMember;
import kr.kro.airbob.domain.reservation.dto.QReservationDateRange;
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
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
	public boolean existsConflictingReservation(
		Long accommodationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Instant now
	) {
		return existsConflictingReservation(
			accommodationId, null, checkInDate, checkOutDate, now);
	}

	@Override
	public boolean existsConflictingReservationExcluding(
		Long accommodationId,
		Long excludedReservationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Instant now
	) {
		return existsConflictingReservation(
			accommodationId, excludedReservationId, checkInDate, checkOutDate, now);
	}

	@Override
	public boolean existsConflictingReservationSnapshot(
		Long accommodationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Instant now
	) {
		Long conflictingReservationId = conflictingReservationQuery(
			accommodationId, null, checkInDate, checkOutDate, now)
			.fetchFirst();

		return conflictingReservationId != null;
	}

	private boolean existsConflictingReservation(
		Long accommodationId,
		Long excludedReservationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Instant now
	) {
		Long conflictingReservationId = conflictingReservationQuery(
			accommodationId, excludedReservationId, checkInDate, checkOutDate, now)
			// A preceding plain read can open a stale MySQL REPEATABLE READ snapshot.
			// The inventory mutex is already held, so use a current read for the final decision.
			.setLockMode(LockModeType.PESSIMISTIC_WRITE)
			.fetchFirst();

		return conflictingReservationId != null;
	}

	private JPAQuery<Long> conflictingReservationQuery(
		Long accommodationId,
		Long excludedReservationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Instant now
	) {
		return queryFactory
			.select(reservation.id)
			.from(reservation)
			.where(
				reservation.accommodation.id.eq(accommodationId),
				excludedReservationId == null ? null : reservation.id.ne(excludedReservationId),
				inventoryOccupyingReservationStatus(now),
				reservation.checkInDate.lt(checkOutDate),
				reservation.checkOutDate.gt(checkInDate)
			);
	}

	@Override
	public boolean existsFutureInventoryReservation(Long accommodationId, Instant now) {
		Integer fetchFirst = queryFactory
			.selectOne()
			.from(reservation)
			.where(
				reservation.accommodation.id.eq(accommodationId),
				reservation.checkOutAt.gt(now),
				inventoryOccupyingReservationStatus(now)
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
				reviewableReservationStatus()
			)
			.fetchFirst();
		return fetchFirst != null;
	}

	@Override
	public boolean existsPastCompletedReservationByGuest(Long accommodationId, Long memberId, Instant now) {
		Integer fetchFirst = queryFactory
			.selectOne()
			.from(reservation)
			.where(
				reservation.accommodation.id.eq(accommodationId),
				reservation.guest.id.eq(memberId),
				reviewableReservationStatus(),
				reservation.checkOutAt.loe(now)
			)
			.fetchFirst();
		return fetchFirst != null;
	}

	@Override
	public List<ReservationDateRange> findActiveReservationRangesByAccommodationId(
		Long accommodationId,
		LocalDate windowStartInclusive,
		LocalDate windowEndExclusive
	) {
		return findReservationRanges(
			reservation.accommodation.id.eq(accommodationId),
			activeReservationStatus(),
			windowStartInclusive,
			windowEndExclusive
		);
	}

	@Override
	public List<ReservationDateRange> findUnavailableReservationRangesByAccommodationId(
		Long accommodationId,
		LocalDate windowStartInclusive,
		LocalDate windowEndExclusive,
		Instant now
	) {
		return findReservationRanges(
			reservation.accommodation.id.eq(accommodationId),
			inventoryOccupyingReservationStatus(now),
			windowStartInclusive,
			windowEndExclusive
		);
	}

	@Override
	public List<ReservationDateRange> findActiveReservationRangesByAccommodationUid(
		UUID accommodationUid,
		LocalDate windowStartInclusive,
		LocalDate windowEndExclusive
	) {
		return findReservationRanges(
			reservation.accommodation.accommodationUid.eq(accommodationUid),
			activeReservationStatus(),
			windowStartInclusive,
			windowEndExclusive
		);
	}

	private List<ReservationDateRange> findReservationRanges(
		BooleanExpression accommodationCondition,
		BooleanExpression statusCondition,
		LocalDate windowStartInclusive,
		LocalDate windowEndExclusive
	) {
		return queryFactory
			.select(new QReservationDateRange(
				reservation.checkInDate,
				reservation.checkOutDate
			))
			.from(reservation)
			.where(
				accommodationCondition,
				statusCondition,
				reservation.checkInDate.lt(windowEndExclusive),
				reservation.checkOutDate.gt(windowStartInclusive)
			)
			.fetch();
	}

	@Override
	public Slice<Reservation> findMyReservationsByGuestIdWithCursor(Long guestId, Long lastId,
		LocalDateTime lastCreatedAt, ReservationFilterType filterType, Instant now, Pageable pageable) {

		List<Reservation> content = queryFactory
			.selectFrom(reservation)
			.leftJoin(reservation.accommodation, accommodation).fetchJoin()
			.leftJoin(accommodation.address, address).fetchJoin()
			.where(
				reservation.guest.id.eq(guestId),
				buildGuestReservationFilter(filterType, now),
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
		LocalDateTime lastCreatedAt, ReservationFilterType filterType, Instant now, Pageable pageable) {

		List<Reservation> content = queryFactory
			.selectFrom(reservation)
			.innerJoin(reservation.accommodation, accommodation).fetchJoin()
			.innerJoin(reservation.guest, guestMember).fetchJoin()
			.where(
				accommodation.member.id.eq(hostId),
				reservation.status.notIn(PAYMENT_PENDING, PAYMENT_PROCESSING),
				buildHostReservationFilter(filterType, now),
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
			.leftJoin(accommodation.address, address).fetchJoin()
			.where(
				reservation.reservationUid.eq(reservationUid),
				accommodation.member.id.eq(hostId)
			)
			.fetchOne();

		return Optional.ofNullable(result);
	}

	private BooleanExpression buildGuestReservationFilter(ReservationFilterType filterType, Instant now) {
		switch (filterType) {
			case PAST:
				// 이전 여행: 유효 예약이면서 체크아웃이 과거
				return activeReservationStatus()
					.and(reservation.checkOutAt.loe(now));
			case CANCELLED:
				return reservation.status.in(CANCELLED, EXPIRED)
					.or(reservation.status.eq(PAYMENT_PENDING)
						.and(reservation.expiresAt.loe(now)));
			case UPCOMING:
				// 다가올 여행: 만료되지 않은 결제 대기 또는 체크아웃 전 유효 예약
				return reservation.status.eq(PAYMENT_PENDING)
					.and(reservation.expiresAt.gt(now))
					.or(reservation.status.eq(PAYMENT_PROCESSING))
					.or(
						activeReservationStatus().and(reservation.checkOutAt.gt(now))
					);
			default:
				return null;
		}
	}

	private BooleanExpression buildHostReservationFilter(ReservationFilterType filterType, Instant now) {
		switch (filterType) {
			case CANCELLED:
				return reservation.status.in(CANCELLED, EXPIRED);
			case PAST:
				return activeReservationStatus().and(reservation.checkOutAt.loe(now));
			case UPCOMING:
				return activeReservationStatus().and(reservation.checkOutAt.gt(now));
			default:
				return null;
		}
	}

	private BooleanExpression activeReservationStatus() {
		return reservation.status.in(CONFIRMED, CANCELLATION_PENDING, CANCELLATION_FAILED);
	}

	private BooleanExpression inventoryOccupyingReservationStatus(Instant now) {
		return activeReservationStatus()
			.or(reservation.status.eq(PAYMENT_PROCESSING))
			.or(reservation.status.eq(PAYMENT_PENDING)
				.and(reservation.expiresAt.gt(now)));
	}

	private BooleanExpression reviewableReservationStatus() {
		return reservation.status.in(ReservationStatus.reviewableStatuses());
	}
}
