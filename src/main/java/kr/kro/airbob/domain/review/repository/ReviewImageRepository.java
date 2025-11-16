package kr.kro.airbob.domain.review.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.image.entity.ReviewImage;

public interface ReviewImageRepository extends JpaRepository<ReviewImage, Long> {

	Optional<ReviewImage> findByIdAndReviewAuthorId(Long id, Long authorId);

	List<ReviewImage> findAllByReview_IdIn(List<Long> reviewIds);
}
