package kr.kro.airbob.core.domain.accommodation.repository.querydsl;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.core.domain.accommodation.AccommodationReviewSummary;
import lombok.RequiredArgsConstructor;

import static kr.kro.airbob.core.domain.accommodation.QAccommodationReviewSummary.accommodationReviewSummary;
import static kr.kro.airbob.core.domain.accommodation.QAccommodation.accommodation;

@RequiredArgsConstructor
public class AccommodationReviewSummaryRepositoryImpl implements AccommodationReviewSummaryRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public List<AccommodationReviewSummary> findAllByAccommodationIdIn(Collection<Long> accommodationIds) {
		if (accommodationIds == null || accommodationIds.isEmpty()) {
			return Collections.emptyList();
		}

		return queryFactory
			.selectFrom(accommodationReviewSummary)
			.join(accommodationReviewSummary.accommodation, accommodation).fetchJoin()
			.where(accommodationReviewSummary.accommodation.id.in(accommodationIds))
			.fetch();
	}
}
