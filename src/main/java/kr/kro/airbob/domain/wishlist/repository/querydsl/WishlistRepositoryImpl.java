package kr.kro.airbob.domain.wishlist.repository.querydsl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.wishlist.entity.QWishlist.*;
import static kr.kro.airbob.domain.wishlist.entity.QWishlistAccommodation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;

import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import kr.kro.airbob.domain.wishlist.repository.projection.QWishlistSummaryProjection;
import kr.kro.airbob.domain.wishlist.repository.projection.WishlistSummaryProjection;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WishlistRepositoryImpl implements WishlistRepositoryCustom {
	private final JPAQueryFactory queryFactory;

	@Override
	public void incrementCountAndSetRepresentative(Long wishlistId, Long accommodationId) {
		queryFactory.update(wishlist)
			.set(wishlist.accommodationCount, wishlist.accommodationCount.add(1))
			.set(wishlist.representativeAccommodationId, accommodationId)
			.set(wishlist.updatedAt, LocalDateTime.now(ZoneOffset.UTC))
			.where(wishlist.id.eq(wishlistId))
			.execute();
	}

	@Override
	public void decrementCount(Long wishlistId) {
		queryFactory.update(wishlist)
			.set(wishlist.accommodationCount, wishlist.accommodationCount.subtract(1))
			.set(wishlist.updatedAt, LocalDateTime.now(ZoneOffset.UTC))
			.where(wishlist.id.eq(wishlistId))
			.execute();
	}

	@Override
	public void updateRepresentative(Long wishlistId, Long accommodationId) {
		JPAUpdateClause clause = queryFactory.update(wishlist)
			.set(wishlist.updatedAt, LocalDateTime.now(ZoneOffset.UTC))
			.where(wishlist.id.eq(wishlistId));

		if (accommodationId == null) {
			clause.setNull(wishlist.representativeAccommodationId);
		} else {
			clause.set(wishlist.representativeAccommodationId, accommodationId);
		}
		clause.execute();
	}

	@Override
	public Slice<WishlistSummaryProjection> findSummariesByMemberIdAndStatusWithCursor(Long memberId,
		WishlistStatus status, Long lastId, LocalDateTime lastCreatedAt, Long accommodationId, Pageable pageable) {
		Expression<Long> membershipId = accommodationId == null
			? Expressions.nullExpression(Long.class) : wishlistAccommodation.id;
		var query = queryFactory
			.select(new QWishlistSummaryProjection(wishlist.id, wishlist.name, wishlist.createdAt,
				wishlist.accommodationCount, accommodation.thumbnailUrl, membershipId))
			.from(wishlist)
			.leftJoin(accommodation).on(accommodation.id.eq(wishlist.representativeAccommodationId));
		if (accommodationId != null) {
			// UNIQUE(wishlist_id, accommodation_id) guarantees at most one matching membership per list.
			query.leftJoin(wishlistAccommodation).on(wishlistAccommodation.wishlist.id.eq(wishlist.id),
				wishlistAccommodation.accommodation.id.eq(accommodationId));
		}
		List<WishlistSummaryProjection> content = query
			.where(wishlist.member.id.eq(memberId), wishlist.status.eq(status), cursorCondition(lastId, lastCreatedAt))
			.orderBy(wishlist.createdAt.desc(), wishlist.id.desc())
			.limit(pageable.getPageSize() + 1)
			.fetch();
		boolean hasNext = content.size() > pageable.getPageSize();
		if (hasNext) {
			content.remove(pageable.getPageSize());
		}
		return new SliceImpl<>(content, pageable, hasNext);
	}

	@Override
	public Slice<Wishlist> findByMemberIdAndStatusWithCursor(Long memberId, WishlistStatus status, Long lastId,
		LocalDateTime lastCreatedAt, Pageable pageable) {
		List<Wishlist> content = queryFactory
			.selectFrom(wishlist)
			.where(
				wishlist.member.id.eq(memberId),
				wishlist.status.eq(status),
				cursorCondition(lastId, lastCreatedAt)
			)
			.orderBy(wishlist.createdAt.desc(), wishlist.id.desc())
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
			return null; // 첫 페이지 조회 시 커서 조건 X
		}

		// createdAt < lastCreatedAt OR (createdAt = lastCreatedAt AND id < lastId)
		return wishlist.createdAt.lt(lastCreatedAt)
			.or(wishlist.createdAt.eq(lastCreatedAt)
				.and(wishlist.id.lt(lastId)));
	}
}
