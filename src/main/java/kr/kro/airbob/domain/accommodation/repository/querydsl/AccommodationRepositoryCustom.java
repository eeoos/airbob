package kr.kro.airbob.domain.accommodation.repository.querydsl;

import java.util.Optional;
import java.util.UUID;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;

public interface AccommodationRepositoryCustom {
    Optional<Accommodation> findWithDetailsByAccommodationUid(UUID accommodationUid);
    Optional<Accommodation> findWithDetailsByAccommodationIdAndStatus(Long accommodationId, AccommodationStatus status);
}
