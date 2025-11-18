package kr.kro.airbob.domain.accommodation.repository.querydsl;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;

public interface AccommodationAmenityRepositoryCustom {

	List<AccommodationAmenity> findAllByAccommodationIdIn(Collection<Long> accommodationIds);
}
