package kr.kro.airbob.core.domain.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.core.domain.review.ReviewImage;

public interface ReviewImageRepository extends JpaRepository<ReviewImage, Long> {

	Optional<ReviewImage> findByIdAndReviewAuthorId(Long id, Long authorId);

	List<ReviewImage> findAllByReviewIdIn(List<Long> reviewIds);
}
