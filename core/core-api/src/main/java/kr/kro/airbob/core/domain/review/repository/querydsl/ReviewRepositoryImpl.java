package kr.kro.airbob.core.domain.review.repository.querydsl;

import static kr.kro.airbob.core.domain.member.QMember.*;
import static kr.kro.airbob.core.domain.review.QReview.*;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.core.domain.review.QReviewItem;
import kr.kro.airbob.core.domain.review.ReviewItem;
import kr.kro.airbob.core.domain.review.ReviewSortType;
import kr.kro.airbob.core.domain.review.ReviewStatus;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReviewRepositoryImpl implements ReviewRepositoryCustom{

	private final JPAQueryFactory jpaQueryFactory;

	@Override
	public Slice<ReviewItem> findByAccommodationIdAndStatusWithCursor(
		Long accommodationId, ReviewStatus status, Long lastId,
		LocalDateTime lastCreatedAt, Integer lastRating, ReviewSortType sortType, Pageable pageable) {

		List<ReviewItem> content = jpaQueryFactory
			.select(new QReviewItem(
				review.id,
				review.rating,
				review.content,
				review.createdAt,
				member.id,
				member.nickname,
				member.thumbnailImageUrl
			))
			.from(review)
			.join(review.author, member)
			.where(
				review.accommodation.id.eq(accommodationId),
				review.status.eq(status),
				getCursorCondition(lastId, lastCreatedAt, lastRating, sortType)
			)
			.orderBy(getOrderSpecifiers(sortType))
			.limit(pageable.getPageSize() + 1)
			.fetch();

		boolean hasNext = content.size() > pageable.getPageSize();
		if (hasNext) {
			content.removeLast();
		}

		return new SliceImpl<>(content, pageable, hasNext);
	}

	private BooleanExpression getCursorCondition(
		Long lastId,
		LocalDateTime lastCreatedAt,
		Integer lastRating,
		ReviewSortType sortType) {

		if (lastId == null) {
			return null;
		}

		if (sortType == ReviewSortType.LATEST) {
			return getLatestCursorCondition(lastId, lastCreatedAt);
		} else if (sortType == ReviewSortType.HIGHEST_RATING) {
			return getRatingCursorCondition(lastId, lastCreatedAt, lastRating, true); // 오름차순
		} else if (sortType == ReviewSortType.LOWEST_RATING) {
			return getRatingCursorCondition(lastId, lastCreatedAt, lastRating, false); // 내림차순
		}

		return null;
	}

	private BooleanExpression getLatestCursorCondition(Long lastId, LocalDateTime lastCreatedAt) {
		if (lastCreatedAt == null) {
			return review.id.lt(lastId);
		}

		return review.createdAt.lt(lastCreatedAt)
			.or(review.createdAt.eq(lastCreatedAt)
				.and(review.id.lt(lastId)));
	}

	private BooleanExpression getRatingCursorCondition(
		Long lastId,
		LocalDateTime lastCreatedAt,
		Integer lastRating,
		boolean isHighestFirst) {

		if (lastRating == null) {
			return getLatestCursorCondition(lastId, lastCreatedAt);
		}

		BooleanExpression ratingCondition = isHighestFirst
			? review.rating.lt(lastRating)
			: review.rating.gt(lastRating);

		BooleanExpression sameRatingCondition = review.rating.eq(lastRating);
		if (lastCreatedAt != null) {
			sameRatingCondition = sameRatingCondition.and(
				review.createdAt.lt(lastCreatedAt)
					.or(review.createdAt.eq(lastCreatedAt)
						.and(review.id.lt(lastId)))
			);
		} else {
			sameRatingCondition = sameRatingCondition.and(review.id.lt(lastId));
		}

		return ratingCondition.or(sameRatingCondition);
	}

	private OrderSpecifier<?>[] getOrderSpecifiers(ReviewSortType sortType) {
		if (sortType == ReviewSortType.LATEST) {
			return new OrderSpecifier[] {review.createdAt.desc(), review.id.desc()};
		} else if (sortType == ReviewSortType.HIGHEST_RATING) {
			return new OrderSpecifier[] {review.rating.desc(), review.createdAt.desc(), review.id.desc()};
		} else if (sortType == ReviewSortType.LOWEST_RATING) {
			return new OrderSpecifier[] {review.rating.asc(), review.createdAt.desc(), review.id.desc()};
		}

		return new OrderSpecifier[] {review.createdAt.desc(), review.id.desc()};
	}
}
