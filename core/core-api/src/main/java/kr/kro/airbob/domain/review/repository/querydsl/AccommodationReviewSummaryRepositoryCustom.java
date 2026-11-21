package kr.kro.airbob.domain.review.repository.querydsl;

import java.util.Collection;
import java.util.List;

import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;

public interface AccommodationReviewSummaryRepositoryCustom {
	List<AccommodationReviewSummary> findAllByAccommodationIdIn(Collection<Long> accommodationIds);
}
