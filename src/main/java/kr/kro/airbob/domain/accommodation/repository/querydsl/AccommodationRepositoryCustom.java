package kr.kro.airbob.domain.accommodation.repository.querydsl;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;

public interface AccommodationRepositoryCustom {
    Optional<Accommodation> findWithDetailsByAccommodationUid(UUID accommodationUid);
    Optional<Accommodation> findWithDetailsByAccommodationIdAndStatus(Long accommodationId, AccommodationStatus status);

    Slice<Accommodation> findMyAccommodationsByHostIdWithCursor(
        Long hostId,
        Long lastId,
        LocalDateTime lastCreatedAt,
        Pageable pageable
    );
}
