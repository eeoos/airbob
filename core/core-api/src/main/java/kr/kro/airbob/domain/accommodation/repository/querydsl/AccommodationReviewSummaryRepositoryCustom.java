package kr.kro.airbob.domain.accommodation.repository.querydsl;

import java.util.Collection;
import java.util.List;

import kr.kro.airbob.domain.accommodation.entity.AccommodationReviewSummary;

public interface AccommodationReviewSummaryRepositoryCustom {
	List<AccommodationReviewSummary> findAllByAccommodationIdIn(Collection<Long> accommodationIds);
}
