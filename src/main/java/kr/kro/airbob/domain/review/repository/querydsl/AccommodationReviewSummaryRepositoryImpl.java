package kr.kro.airbob.domain.review.repository.querydsl;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import lombok.RequiredArgsConstructor;

import static kr.kro.airbob.domain.review.entity.QAccommodationReviewSummary.accommodationReviewSummary;
import static kr.kro.airbob.domain.accommodation.entity.QAccommodation.accommodation;

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
