package kr.kro.airbob.core.domain.accommodation.repository.querydsl;

import java.util.Collection;
import java.util.List;

import kr.kro.airbob.core.domain.accommodation.AccommodationReviewSummary;

public interface AccommodationReviewSummaryRepositoryCustom {
	List<AccommodationReviewSummary> findAllByAccommodationIdIn(Collection<Long> accommodationIds);
}
