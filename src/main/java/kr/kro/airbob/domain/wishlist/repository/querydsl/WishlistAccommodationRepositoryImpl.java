package kr.kro.airbob.domain.wishlist.repository.querydsl;

import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.*;
import static kr.kro.airbob.domain.review.entity.QAccommodationReviewSummary.*;
import static kr.kro.airbob.domain.wishlist.entity.QWishlistAccommodation.*;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

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
				accommodation.id,
				accommodation.name,
				accommodationReviewSummary.averageRating,
				wishlistAccommodation.createdAt
			))
			.from(wishlistAccommodation)
			.join(wishlistAccommodation.accommodation, accommodation)
			.leftJoin(accommodationReviewSummary)
			.on(accommodationReviewSummary.accommodationId.eq(accommodation.id))
			.where(
				wishlistAccommodation.wishlist.id.eq(wishlistId),
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

	private BooleanExpression cursorCondition(Long lastId, LocalDateTime lastCreatedAt) {
		if (lastId == null || lastCreatedAt == null) {
			return null;
		}

		return wishlistAccommodation.createdAt.lt(lastCreatedAt)
			.or(wishlistAccommodation.createdAt.eq(lastCreatedAt)
				.and(wishlistAccommodation.id.lt(lastId)));
	}
}
