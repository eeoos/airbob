package kr.kro.airbob.domain.review.repository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.dto.ReviewSummaryRow;

import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.querydsl.ReviewRepositoryCustom;

public interface ReviewRepository extends JpaRepository<Review, Long>, ReviewRepositoryCustom {

	boolean existsByAccommodationIdAndAuthorIdAndStatus(Long accommodationId, Long authorId, ReviewStatus status);

	Optional<Review> findByIdAndAuthorId(Long reviewId, Long memberId);

	// 비교용 원본 집계: 요청한 숙소만 한 번에 집계해 목록의 N+1을 피한다.
	@Query(value = """
		SELECT accommodation_id AS accommodationId, COUNT(*) AS totalCount,
			ROUND(AVG(rating), 2) AS averageRating
		FROM review
		WHERE accommodation_id IN (:accommodationIds) AND status = 'PUBLISHED'
		GROUP BY accommodation_id
		""", nativeQuery = true)
	List<ReviewSummaryRow> aggregatePublishedSummaries(@Param("accommodationIds") Collection<Long> accommodationIds);

	default Map<Long, ReviewResponse.ReviewSummary> findPublishedSummaryMap(Collection<Long> accommodationIds) {
		if (accommodationIds.isEmpty()) {
			return Map.of();
		}
		return aggregatePublishedSummaries(accommodationIds).stream()
			.collect(Collectors.toMap(ReviewSummaryRow::getAccommodationId,
				row -> ReviewResponse.ReviewSummary.of(Math.toIntExact(row.getTotalCount()), row.getAverageRating())));
	}
}
