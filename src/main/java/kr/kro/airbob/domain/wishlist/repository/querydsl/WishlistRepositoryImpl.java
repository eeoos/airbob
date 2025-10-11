package kr.kro.airbob.domain.wishlist.repository.querydsl;

import static kr.kro.airbob.domain.wishlist.entity.QWishlist.*;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class WishlistRepositoryImpl implements WishlistRepositoryCustom {
	private final JPAQueryFactory queryFactory;

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
