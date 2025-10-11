package kr.kro.airbob.domain.wishlist.repository.querydsl;

import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;

public interface WishlistAccommodationRepositoryCustom {
	Slice<WishlistAccommodationResponse.WishlistAccommodationInfo> findAccommodationsInWishlist(
		Long wishlistId, Long lastId, LocalDateTime lastCreatedAt, Pageable pageable
	);
}
