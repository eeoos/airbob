package kr.kro.airbob.domain.review.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import kr.kro.airbob.domain.review.entity.Review;
import kr.kro.airbob.domain.review.entity.ReviewStatus;
import kr.kro.airbob.domain.review.repository.querydsl.ReviewRepositoryCustom;

public interface ReviewRepository extends JpaRepository<Review, Long>, ReviewRepositoryCustom {

	boolean existsByAccommodationIdAndAuthorIdAndStatus(Long accommodationId, Long authorId, ReviewStatus status);

	Optional<Review> findByIdAndAuthorId(Long reviewId, Long memberId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Review r where r.id = :reviewId and r.author.id = :memberId")
	Optional<Review> findByIdAndAuthorIdForUpdate(@Param("reviewId") Long reviewId, @Param("memberId") Long memberId);
}
