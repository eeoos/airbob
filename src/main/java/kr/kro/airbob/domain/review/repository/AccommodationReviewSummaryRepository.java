package kr.kro.airbob.domain.review.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.querydsl.AccommodationReviewSummaryRepositoryCustom;

public interface AccommodationReviewSummaryRepository extends JpaRepository<AccommodationReviewSummary, Long>,
	AccommodationReviewSummaryRepositoryCustom {

	Optional<AccommodationReviewSummary> findByAccommodationId(Long accommodationId);

	Optional<AccommodationReviewSummary> findByAccommodation_AccommodationUid(UUID accommodationUid);

	List<AccommodationReviewSummary> findByAccommodationIdIn(List<Long> accommodationId);

	// ===== 원자적 집계 갱신 (낙관적 락 대체) =====
	// MySQL ON DUPLICATE KEY UPDATE / UPDATE 는 SET 절을 좌→우로 평가하므로,
	// average_rating 을 먼저 둬서 증감 이전(옛) rating_sum/total_review_count 로 계산한다.

	// 리뷰 생성: 행이 없으면 INSERT, 있으면 원자적 증가. 첫 리뷰 동시 작성의 PK 중복을 upsert로 병합.
	@Modifying
	@Query(value = """
		INSERT INTO accommodation_review_summary
			(accommodation_id, total_review_count, rating_sum, average_rating, created_at, updated_at)
		VALUES (:accommodationId, 1, :rating, :rating, NOW(6), NOW(6))
		ON DUPLICATE KEY UPDATE
			average_rating     = ROUND((rating_sum + :rating) / (total_review_count + 1), 2),
			total_review_count = total_review_count + 1,
			rating_sum         = rating_sum + :rating,
			updated_at         = NOW(6)
		""", nativeQuery = true)
	void applyNewReview(@Param("accommodationId") Long accommodationId, @Param("rating") int rating);

	// 리뷰 삭제: 원자적 감소(0이 되면 average 0). 행 제거는 deleteIfEmpty 로.
	@Modifying
	@Query(value = """
		UPDATE accommodation_review_summary
		SET average_rating = CASE WHEN total_review_count - 1 <= 0 THEN 0
								  ELSE ROUND((rating_sum - :rating) / (total_review_count - 1), 2) END,
			total_review_count = total_review_count - 1,
			rating_sum         = rating_sum - :rating,
			updated_at         = NOW(6)
		WHERE accommodation_id = :accommodationId
		""", nativeQuery = true)
	void removeReview(@Param("accommodationId") Long accommodationId, @Param("rating") int rating);

	// 리뷰가 0건이 된 요약 행 제거(기존 동작 유지)
	@Modifying
	@Query(value = "DELETE FROM accommodation_review_summary "
		+ "WHERE accommodation_id = :accommodationId AND total_review_count <= 0", nativeQuery = true)
	void deleteIfEmpty(@Param("accommodationId") Long accommodationId);

	// 평점 수정: 개수 불변, rating_sum/average_rating 만 원자적 조정
	@Modifying
	@Query(value = """
		UPDATE accommodation_review_summary
		SET average_rating = ROUND((rating_sum - :oldRating + :newRating) / total_review_count, 2),
			rating_sum     = rating_sum - :oldRating + :newRating,
			updated_at     = NOW(6)
		WHERE accommodation_id = :accommodationId
		""", nativeQuery = true)
	void applyRatingChange(@Param("accommodationId") Long accommodationId,
		@Param("oldRating") int oldRating, @Param("newRating") int newRating);
}
