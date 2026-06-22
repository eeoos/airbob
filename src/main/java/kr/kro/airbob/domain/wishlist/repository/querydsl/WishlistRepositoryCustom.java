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

	// ===== 반정규화 카운터 원자적 유지 (DB단 bulk UPDATE) =====

	// 숙소 추가: 개수 +1, 대표를 방금 추가한 숙소(=최신)로 설정
	void incrementCountAndSetRepresentative(Long wishlistId, Long accommodationId);

	// 숙소 삭제: 개수 -1
	void decrementCount(Long wishlistId);

	// 대표 숙소 재선정(accommodationId == null 이면 NULL)
	void updateRepresentative(Long wishlistId, Long accommodationId);
}
