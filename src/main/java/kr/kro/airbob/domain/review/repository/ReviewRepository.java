package kr.kro.airbob.domain.review.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.querydsl.ReviewRepositoryCustom;

public interface ReviewRepository extends JpaRepository<Review, Long>, ReviewRepositoryCustom {

	boolean existsByAccommodationIdAndAuthorIdAndStatus(Long accommodationId, Long authorId, ReviewStatus status);

	@Query("select r.author.id from Review r where r.id = :reviewId")
	Optional<Long> findMemberIdByReviewId(@Param("reviewId") Long reviewId);
}
