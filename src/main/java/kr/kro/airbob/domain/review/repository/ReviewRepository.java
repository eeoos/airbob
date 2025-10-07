package kr.kro.airbob.domain.review.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.review.Review;
import kr.kro.airbob.domain.review.repository.querydsl.ReviewRepositoryCustom;

public interface ReviewRepository extends JpaRepository<Review, Long>, ReviewRepositoryCustom {

	@Query("select r.accommodation.id from Review r where r.id = :reviewId")
	Long findAccommodationIdByReviewId(@Param("reviewId") Long reviewId);

	@Query("select r.author.id from Review r where r.id = :reviewId")
	Optional<Long> findMemberIdByReviewId(@Param("reviewId") Long reviewId);

	boolean existsByAccommodationIdAndAuthorId(Long accommodationId, Long authorId);
}
