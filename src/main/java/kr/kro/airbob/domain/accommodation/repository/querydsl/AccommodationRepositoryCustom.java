package kr.kro.airbob.domain.accommodation.repository.querydsl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.repository.projection.AccommodationDetailProjection;
import kr.kro.airbob.domain.accommodation.repository.projection.HostAccommodationProjection;
import kr.kro.airbob.domain.accommodation.repository.projection.HostAccommodationDetailProjection;
import kr.kro.airbob.domain.accommodation.repository.projection.PublicAccommodationDetailProjection;

public interface AccommodationRepositoryCustom {
    Optional<Accommodation> findWithDetailsByAccommodationUid(UUID accommodationUid);
    Optional<PublicAccommodationDetailProjection> findWithDetailsByAccommodationIdAndStatus(
        Long accommodationId,
        AccommodationStatus status
    );

    Slice<HostAccommodationProjection> findMyAccommodationsByHostIdWithCursor(
        Long hostId,
        Long lastId,
        LocalDateTime lastCreatedAt,
        AccommodationStatus status,
        Pageable pageable
    );

	List<AccommodationDetailProjection> findWithAddressAndReviewSummaryByIdInAndStatus(
		List<Long> accommodationIds, AccommodationStatus status);

	Optional<HostAccommodationDetailProjection> findWithDetailsByIdAndHostId(Long accommodationId, Long hostId);

	Page<Accommodation> findForIndexing(Pageable pageable);

}
