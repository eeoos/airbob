package kr.kro.airbob.domain.wishlist.repository.querydsl;

import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.wishlist.entity.Wishlist;
import kr.kro.airbob.domain.wishlist.entity.WishlistStatus;

public interface WishlistRepositoryCustom {

	Slice<Wishlist> findByMemberIdAndStatusWithCursor(
		Long memberId,
		WishlistStatus status,
		Long lastId,
		LocalDateTime lastCreatedAt,
		Pageable pageable
	);
}
