package kr.kro.airbob.core.domain.review.repository.querydsl;

import java.time.LocalDateTime;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.core.domain.review.ReviewItem;
import kr.kro.airbob.core.domain.review.ReviewSortType;
import kr.kro.airbob.core.domain.review.ReviewStatus;

public interface ReviewRepositoryCustom {

	Slice<ReviewItem> findByAccommodationIdAndStatusWithCursor(
		Long accommodationId, ReviewStatus status, Long lastId,
		LocalDateTime lastCreatedAt, Integer lastRating, ReviewSortType sortType, Pageable pageable);
}
