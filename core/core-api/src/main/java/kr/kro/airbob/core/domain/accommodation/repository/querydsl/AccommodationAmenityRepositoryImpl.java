package kr.kro.airbob.core.domain.accommodation.repository.querydsl;

import static kr.kro.airbob.core.domain.accommodation.QAccommodation.*;
import static kr.kro.airbob.core.domain.accommodation.QAccommodationAmenity.*;
import static kr.kro.airbob.core.domain.accommodation.QAddress.*;
import static kr.kro.airbob.core.domain.accommodation.QOccupancyPolicy.*;
import static kr.kro.airbob.core.domain.member.QMember.*;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.core.domain.accommodation.AccommodationAmenity;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AccommodationAmenityRepositoryImpl implements AccommodationAmenityRepositoryCustom {

	private final JPAQueryFactory jpaQueryFactory;

	@Override
	public List<AccommodationAmenity> findAllByAccommodationIdIn(Collection<Long> accommodationIds) {
		if (accommodationIds == null || accommodationIds.isEmpty()) {
			return Collections.emptyList();
		}

		// amenity_code 를 직접 보관하므로 amenity 조인 불필요
		return jpaQueryFactory
			.selectFrom(accommodationAmenity)
			.where(accommodationAmenity.accommodation.id.in(accommodationIds))
			.fetch();
	}
}
