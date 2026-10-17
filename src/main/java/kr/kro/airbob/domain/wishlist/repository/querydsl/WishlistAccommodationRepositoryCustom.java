package kr.kro.airbob.domain.wishlist.repository.querydsl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;

public interface WishlistAccommodationRepositoryCustom {
	Slice<WishlistAccommodationResponse.WishlistAccommodationInfo> findAccommodationsInWishlist(
		Long wishlistId, Long lastId, LocalDateTime lastCreatedAt, Pageable pageable
	);

	Set<Long> findAccommodationIdsByMemberIdAndAccommodationIds(Long memberId, List<Long> accommodationIds);

	// 대표 숙소 재선정용: 위시리스트 내 가장 최근에 추가된 숙소 id (없으면 null)
	Long findLatestAccommodationId(Long wishlistId);
}
