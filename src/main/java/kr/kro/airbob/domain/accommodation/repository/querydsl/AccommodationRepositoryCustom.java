package kr.kro.airbob.domain.accommodation.repository.querydsl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import kr.kro.airbob.domain.accommodation.dto.AccommodationRequest.AccommodationSearchConditionDto;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse.AccommodationSearchResponseDto;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;

public interface AccommodationRepositoryCustom {
    List<AccommodationSearchResponseDto> searchByFilter(AccommodationSearchConditionDto condition, Pageable pageable);
    Optional<Accommodation> findWithDetailsByAccommodationUid(UUID accommodationUid);
}
