package kr.kro.airbob.domain.review.repository.querydsl;

import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.review.entity.ReviewSortType;
import kr.kro.airbob.domain.review.dto.ReviewResponse;

public interface ReviewRepositoryCustom {

	Slice<ReviewResponse.ReviewInfo> findByAccommodationIdWithCursor(
		Long accommodationId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		Integer lastRating,
		ReviewSortType sortType,
		Pageable pageable
	);
}
