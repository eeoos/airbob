package kr.kro.airbob.domain.review.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.querydsl.AccommodationReviewSummaryRepositoryCustom;

public interface AccommodationReviewSummaryRepository extends JpaRepository<AccommodationReviewSummary, Long>,
	AccommodationReviewSummaryRepositoryCustom {

	Optional<AccommodationReviewSummary> findByAccommodationId(Long accommodationId);

	Optional<AccommodationReviewSummary> findByAccommodation_AccommodationUid(UUID accommodationUid);

	List<AccommodationReviewSummary> findByAccommodationIdIn(List<Long> accommodationId);

}
