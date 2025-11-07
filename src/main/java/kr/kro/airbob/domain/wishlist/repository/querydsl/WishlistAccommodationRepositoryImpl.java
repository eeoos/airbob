package kr.kro.airbob.domain.wishlist.repository.querydsl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.accommodation.entity.QAddress.*;
import static kr.kro.airbob.domain.member.entity.QMember.*;
import static kr.kro.airbob.domain.review.entity.QAccommodationReviewSummary.*;
import static kr.kro.airbob.domain.wishlist.entity.QWishlist.*;
import static kr.kro.airbob.domain.wishlist.entity.QWishlistAccommodation.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.group.GroupBy;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.wishlist.dto.QWishlistAccommodationResponse_WishlistAccommodationInfo;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WishlistAccommodationRepositoryImpl implements WishlistAccommodationRepositoryCustom{

	private final JPAQueryFactory queryFactory;

	@Override
	public Slice<WishlistAccommodationResponse.WishlistAccommodationInfo> findAccommodationsInWishlist(Long wishlistId,
		Long lastId, LocalDateTime lastCreatedAt, Pageable pageable) {

		List<WishlistAccommodationResponse.WishlistAccommodationInfo> content = queryFactory
			.select(new QWishlistAccommodationResponse_WishlistAccommodationInfo(
				wishlistAccommodation.id,
				wishlistAccommodation.memo,
				accommodation,
				accommodationReviewSummary.averageRating,
				accommodationReviewSummary.totalReviewCount,
				wishlistAccommodation.createdAt
			))
			.from(wishlistAccommodation)
			.join(wishlistAccommodation.accommodation, accommodation)
			.join(accommodation.address, address).fetchJoin()
			.leftJoin(accommodationReviewSummary)
			.on(accommodationReviewSummary.accommodationId.eq(accommodation.id))
			.where(
				wishlist.id.eq(wishlistId),
				accommodation.status.eq(AccommodationStatus.PUBLISHED),
				cursorCondition(lastId, lastCreatedAt)
			)
			.orderBy(wishlistAccommodation.createdAt.desc(), wishlistAccommodation.id.desc())
			.limit(pageable.getPageSize() + 1)
			.fetch();

		boolean hasNext = content.size() > pageable.getPageSize();
		if (hasNext) {
			content.remove(pageable.getPageSize());
		}

		return new SliceImpl<>(content, pageable, hasNext);
	}

	@Override
	public Map<Long, Long> countByWishlistIds(List<Long> wishlistIds) {
		return queryFactory
			.select(
				wishlistAccommodation.wishlist.id,
				wishlistAccommodation.count()
			)
			.from(wishlistAccommodation)
			.join(wishlistAccommodation.accommodation, accommodation)
			.where(
				wishlistAccommodation.wishlist.id.in(wishlistIds),
				accommodation.status.eq(AccommodationStatus.PUBLISHED)
			)
			.groupBy(wishlistAccommodation.wishlist.id)
			.transform(GroupBy.groupBy(wishlistAccommodation.wishlist.id).as(wishlistAccommodation.count()));
	}

	@Override
	public Set<Long> findAccommodationIdsByMemberIdAndAccommodationIds(Long memberId, List<Long> accommodationIds) {
		List<Long> result = queryFactory
			.select(wishlistAccommodation.accommodation.id)
			.from(wishlistAccommodation)
			.join(wishlistAccommodation.wishlist, wishlist)
			.join(wishlist.member, member)
			.join(wishlistAccommodation.accommodation, accommodation)
			.where(
				member.id.eq(memberId),
				accommodation.id.in(accommodationIds),
				accommodation.status.eq(AccommodationStatus.PUBLISHED)
			)
			.fetch();

		return new HashSet<>(result);
	}

	private BooleanExpression cursorCondition(Long lastId, LocalDateTime lastCreatedAt) {
		if (lastId == null || lastCreatedAt == null) {
			return null;
		}

		return wishlistAccommodation.createdAt.lt(lastCreatedAt)
			.or(wishlistAccommodation.createdAt.eq(lastCreatedAt)
				.and(wishlistAccommodation.id.lt(lastId)));
	}
}
