package kr.kro.airbob.core.domain.accommodation.repository.querydsl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.core.domain.accommodation.HostAccommodationDetailProjection;
import kr.kro.airbob.core.domain.accommodation.HostAccommodationProjection;
import kr.kro.airbob.core.domain.accommodation.RecentlyViewedAccommodationProjection;
import kr.kro.airbob.core.domain.accommodation.repository.projection.PublicAccommodationDetailProjection;

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

	List<RecentlyViewedAccommodationProjection> findWithAddressAndReviewSummaryByIdInAndStatus(
		List<Long> accommodationIds, AccommodationStatus status);

	Optional<HostAccommodationDetailProjection> findWithDetailsByIdAndHostId(Long accommodationId, Long hostId);

	Page<Accommodation> findForIndexing(Pageable pageable);

}
