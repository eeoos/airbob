package kr.kro.airbob.domain.review.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.review.AccommodationReviewSummary;

public interface AccommodationReviewSummaryRepository extends JpaRepository<AccommodationReviewSummary, Long> {

	Optional<AccommodationReviewSummary> findByAccommodationId(Long accommodationId);

	Optional<AccommodationReviewSummary> findByAccommodation_AccommodationUid(UUID accommodationUid);

	List<AccommodationReviewSummary> findByAccommodationIdIn(List<Long> accommodationId);

}
