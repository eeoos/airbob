package kr.kro.airbob.domain.accommodation.repository.querydsl;

import java.util.Optional;
import java.util.UUID;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;

public interface AccommodationRepositoryCustom {
    Optional<Accommodation> findWithDetailsByAccommodationUid(UUID accommodationUid);
}
