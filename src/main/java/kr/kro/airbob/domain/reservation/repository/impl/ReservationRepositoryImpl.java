package kr.kro.airbob.domain.reservation.repository.impl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QAddress.*;
import static kr.kro.airbob.domain.member.entity.QMember.*;
import static kr.kro.airbob.domain.payment.entity.QPayment.payment;
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
import com.querydsl.jpa.impl.JPAQueryFactory;

import jakarta.persistence.EntityManager;
import kr.kro.airbob.domain.member.entity.QMember;
import kr.kro.airbob.domain.reservation.dto.QReservationDateRange;
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.ReservationRepositoryCustom;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationListProjection;
import kr.kro.airbob.domain.reservation.repository.projection.GuestReservationDetailProjection;
import kr.kro.airbob.domain.reservation.repository.projection.QGuestReservationDetailProjection;
import kr.kro.airbob.domain.reservation.repository.projection.QGuestReservationListProjection;
import kr.kro.airbob.domain.reservation.repository.projection.HostReservationDetailProjection;
import kr.kro.airbob.domain.reservation.repository.projection.HostReservationListProjection;
import kr.kro.airbob.domain.reservation.repository.projection.QHostReservationDetailProjection;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReservationRepositoryImpl implements ReservationRepositoryCustom {

	private static final List<ReservationStatus> ACTIVE_RESERVATION_STATUSES =
		List.of(CONFIRMED, CANCELLATION_PENDING, CANCELLATION_FAILED);

	private final JPAQueryFactory queryFactory;
	private final EntityManager entityManager;

	private final QMember guestMember = new QMember("guestMember");

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
	public Slice<GuestReservationListProjection> findMyReservationsByGuestIdWithCursor(Long guestId, Long lastId,
		LocalDateTime lastCreatedAt, ReservationFilterType filterType, Instant now, Pageable pageable) {

		List<GuestReservationListProjection> content = queryFactory
			.select(new QGuestReservationListProjection(
				reservation.id, reservation.reservationUid, reservation.checkInDate, reservation.checkOutDate,
				reservation.timeZoneId, reservation.status, reservation.expiresAt, reservation.createdAt,
				accommodation.id, accommodation.name, accommodation.thumbnailUrl
			))
			.from(reservation)
			.leftJoin(reservation.accommodation, accommodation)
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
	public Optional<GuestReservationDetailProjection> findReservationDetailByUidAndGuestId(UUID reservationUid, Long guestId) {

		GuestReservationDetailProjection result = queryFactory
			.select(new QGuestReservationDetailProjection(
				reservation.reservationUid, reservation.reservationCode, reservation.status,
				reservation.expiresAt, reservation.totalPrice, reservation.createdAt, reservation.guestCount,
				reservation.checkInAt, reservation.checkOutAt, reservation.timeZoneId, reservation.message,
				accommodation.id, accommodation.name, accommodation.thumbnailUrl, accommodation.status,
				address.country, address.state, address.city, address.district, address.street, address.detail,
				address.postalCode, address.latitude, address.longitude,
				member.id, member.nickname, member.thumbnailImageUrl,
				payment.id, payment.method, payment.amount, payment.status, payment.approvedAt
			))
			.from(reservation)
			.leftJoin(reservation.accommodation, accommodation)
			.leftJoin(accommodation.address, address)
			.leftJoin(accommodation.member, member)
			.leftJoin(payment).on(payment.reservation.id.eq(reservation.id))
			.where(
				reservation.reservationUid.eq(reservationUid),
				reservation.guest.id.eq(guestId)
			)
			.fetchOne();

		return Optional.ofNullable(result);
	}

	@Override
	public Slice<HostReservationListProjection> findHostReservationsByHostIdWithCursor(Long hostId, Long lastId,
		LocalDateTime lastCreatedAt, ReservationFilterType filterType, Instant now, Pageable pageable) {

		boolean hasCursor = lastId != null && lastCreatedAt != null;
		String cursorFilter = hasCursor
			? "and (candidate.createdAt < :lastCreatedAt or (candidate.createdAt = :lastCreatedAt and candidate.id < :lastId))"
			: "";

		// HQL 파생 테이블로 후보 제한 후 동일 SQL 스냅샷에서 표시 정보 조인
		// 게스트 조인을 뒤로 미루기 위한 전제: guest_id NOT NULL 및 외래 키
		var query = entityManager.createQuery("""
			select new kr.kro.airbob.domain.reservation.repository.projection.HostReservationListProjection(
				r.id, r.reservationUid, r.reservationCode, r.totalPrice, r.currency, r.guestCount,
				r.checkInDate, r.checkOutDate, r.timeZoneId, r.status, r.createdAt,
				guest.id, guest.nickname, guest.thumbnailImageUrl,
				display.id, display.name, display.thumbnailUrl
			)
			from (
				select candidate.id as id, candidate.createdAt as createdAt
				from Reservation candidate
				join candidate.accommodation owner
				where owner.member.id = :hostId
					and candidate.status not in :excludedStatuses
					%s
					%s
				order by candidate.createdAt desc, candidate.id desc
				limit :fetchLimit
			) page
			join Reservation r on r.id = page.id
			join r.accommodation display
			join r.guest guest
			order by page.createdAt desc, page.id desc
			""".formatted(buildHostReservationFilter(filterType), cursorFilter), HostReservationListProjection.class)
			.setParameter("hostId", hostId)
			.setParameter("excludedStatuses", List.of(PAYMENT_PENDING, PAYMENT_PROCESSING))
			.setParameter("fetchLimit", Math.addExact(pageable.getPageSize(), 1));

		if (hasCursor) {
			query.setParameter("lastCreatedAt", lastCreatedAt);
			query.setParameter("lastId", lastId);
		}
		if (filterType != null) {
			query.setParameter("filterStatuses", filterType == ReservationFilterType.CANCELLED
				? List.of(CANCELLED, EXPIRED) : ACTIVE_RESERVATION_STATUSES);
			if (filterType != ReservationFilterType.CANCELLED) {
				query.setParameter("now", now);
			}
		}
		List<HostReservationListProjection> content = query.getResultList();

		boolean hasNext = content.size() > pageable.getPageSize();
		if (hasNext) {
			content.remove(pageable.getPageSize());
		}

		return new SliceImpl<>(content, pageable, hasNext);
	}

	@Override
	public Optional<HostReservationDetailProjection> findHostReservationDetailByUidAndHostId(
		UUID reservationUid, Long hostId
	) {
		HostReservationDetailProjection result = queryFactory
			.select(new QHostReservationDetailProjection(
				reservation.reservationUid, reservation.reservationCode, reservation.status, reservation.createdAt,
				reservation.guestCount, reservation.checkInAt, reservation.checkOutAt, reservation.timeZoneId,
				reservation.message,
				accommodation.id, accommodation.name, accommodation.thumbnailUrl,
				address.country, address.state, address.city, address.district, address.street, address.detail,
				address.postalCode,
				guestMember.id, guestMember.nickname, guestMember.thumbnailImageUrl,
				payment.amount
			))
			.from(reservation)
			.innerJoin(reservation.accommodation, accommodation)
			.innerJoin(reservation.guest, guestMember)
			.leftJoin(accommodation.address, address)
			.leftJoin(payment).on(payment.reservation.id.eq(reservation.id))
			.where(
				reservation.reservationUid.eq(reservationUid),
				accommodation.member.id.eq(hostId)
			)
			.fetchOne();

		return Optional.ofNullable(result);
	}

	private BooleanExpression buildGuestReservationFilter(ReservationFilterType filterType, Instant now) {
		if (filterType == null) {
			return null;
		}
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

	private String buildHostReservationFilter(ReservationFilterType filterType) {
		if (filterType == null) {
			return "";
		}
		return switch (filterType) {
			case CANCELLED -> "and candidate.status in :filterStatuses";
			case PAST -> "and candidate.status in :filterStatuses and candidate.checkOutAt <= :now";
			case UPCOMING -> "and candidate.status in :filterStatuses and candidate.checkOutAt > :now";
		};
	}

	private BooleanExpression activeReservationStatus() {
		return reservation.status.in(ACTIVE_RESERVATION_STATUSES);
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
