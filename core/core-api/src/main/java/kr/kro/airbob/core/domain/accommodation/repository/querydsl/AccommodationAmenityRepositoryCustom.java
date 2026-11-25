package kr.kro.airbob.core.domain.accommodation.repository.querydsl;

import java.util.Collection;
import java.util.List;

import kr.kro.airbob.core.domain.accommodation.AccommodationAmenity;

public interface AccommodationAmenityRepositoryCustom {

	List<AccommodationAmenity> findAllByAccommodationIdIn(Collection<Long> accommodationIds);
}
