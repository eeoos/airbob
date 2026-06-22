package kr.kro.airbob.domain.review.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.review.dto.ReviewSummaryRow;
import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.querydsl.ReviewRepositoryCustom;

public interface ReviewRepository extends JpaRepository<Review, Long>, ReviewRepositoryCustom {

	boolean existsByAccommodationIdAndAuthorIdAndStatus(Long accommodationId, Long authorId, ReviewStatus status);

	Optional<Review> findByIdAndAuthorId(Long reviewId, Long memberId);

	// 성능 비교용 "before": 반정규화 테이블(accommodation_review_summary) 대신
	// review 테이블에서 직접 COUNT/AVG 집계(게시 상태만). after = summaryRepository 조회.
	@Query(value = """
		SELECT COUNT(*) AS totalCount,
			COALESCE(ROUND(AVG(rating), 2), 0) AS averageRating
		FROM review
		WHERE accommodation_id = :accommodationId AND status = 'PUBLISHED'
		""", nativeQuery = true)
	ReviewSummaryRow aggregateSummaryNaive(@Param("accommodationId") Long accommodationId);
}
