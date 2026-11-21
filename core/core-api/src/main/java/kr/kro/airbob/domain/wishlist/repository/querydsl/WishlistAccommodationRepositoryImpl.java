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
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAQuery;

import jakarta.persistence.LockModeType;

import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.wishlist.dto.QWishlistAccommodationResponse_WishlistAccommodationInfo;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WishlistAccommodationRepositoryImpl implements WishlistAccommodationRepositoryCustom{

	private final JPAQueryFactory queryFactory;

	@Override
	public Slice<WishlistAccommodationResponse.WishlistAccommodationInfo> findAccommodationsInWishlist(Long wishlistId,
		Long lastId, LocalDateTime lastCreatedAt, Pageable pageable) {

		JPAQuery<WishlistAccommodationResponse.WishlistAccommodationInfo> query = queryFactory
			.select(new QWishlistAccommodationResponse_WishlistAccommodationInfo(
				wishlistAccommodation.id,
				wishlistAccommodation.memo,
				accommodation.id,
				accommodation.name,
				accommodation.thumbnailUrl,
				address.country,
				address.state,
				address.city,
				address.district,
				accommodationReviewSummary.averageRating,
				accommodationReviewSummary.totalReviewCount,
				wishlistAccommodation.createdAt
			))
			.from(wishlistAccommodation)
			.join(wishlistAccommodation.accommodation, accommodation)
			.join(accommodation.address, address)
			.leftJoin(accommodationReviewSummary)
				.on(accommodationReviewSummary.accommodationId.eq(accommodation.id));
		List<WishlistAccommodationResponse.WishlistAccommodationInfo> content = query.where(
				wishlistAccommodation.wishlist.id.eq(wishlistId),
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
	public Set<Long> findAccommodationIdsByMemberIdAndAccommodationIds(Long memberId, List<Long> accommodationIds) {
		List<Long> result = queryFactory
			.select(wishlistAccommodation.accommodation.id)
			.from(wishlistAccommodation)
			.join(wishlistAccommodation.wishlist, wishlist)
			.join(wishlist.member, member)
			.join(wishlistAccommodation.accommodation, accommodation)
			.where(
				member.id.eq(memberId),
				wishlist.status.eq(WishlistStatus.ACTIVE),
				accommodation.id.in(accommodationIds),
				accommodation.status.eq(AccommodationStatus.PUBLISHED)
			)
			.fetch();

		return new HashSet<>(result);
	}

	@Override
	public Long findLatestAccommodationId(Long wishlistId) {
		return queryFactory
			.select(wishlistAccommodation.accommodation.id)
			.from(wishlistAccommodation)
			.where(wishlistAccommodation.wishlist.id.eq(wishlistId))
			.orderBy(wishlistAccommodation.createdAt.desc(), wishlistAccommodation.id.desc())
			// 부모 목록 잠금 이후 현재 남은 연결로 대표를 선정한다(RR의 이전 스냅샷 재사용 방지).
			.setLockMode(LockModeType.PESSIMISTIC_WRITE)
			.fetchFirst();
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
