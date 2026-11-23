package kr.kro.airbob.domain.accommodation.service;

import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.api.controller.v1.image.response.ImageResponse;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.port.AccommodationAvailability;
import kr.kro.airbob.domain.accommodation.port.AccommodationAvailabilityReader;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.HostAccommodationProjection;
import kr.kro.airbob.domain.accommodation.repository.projection.HostAccommodationDetailProjection;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccommodationQueryService {

	private final AccommodationRepository accommodationRepository;
	private final AccommodationAvailabilityReader availabilityReader;
	private final CursorPageInfoCreator cursorPageInfoCreator;
	private final AccommodationDetailReader accommodationDetailReader;
	private final AccommodationDetailCache accommodationDetailCache;

	public AccommodationResponse.DetailInfo findAccommodation(Long accommodationId, Long viewerId) {
		AccommodationDetailSnapshot snapshot = accommodationDetailCache.getOrLoad(
			accommodationId,
			() -> accommodationDetailReader.load(accommodationId)
		);
		boolean isInWishlist = viewerId != null
			&& accommodationDetailReader.isInWishlist(accommodationId, viewerId);

		return AccommodationResponse.DetailInfo.from(snapshot, isInWishlist);
	}

	@Transactional(readOnly = true)
	public AccommodationResponse.Availability findAccommodationAvailability(Long accommodationId) {
		String timeZoneId = accommodationRepository
			.findBookingProjectionByIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new)
			.timeZoneId();
		AccommodationAvailability availability = availabilityReader.readAvailability(accommodationId, timeZoneId);
		List<AccommodationResponse.UnavailableDateRange> unavailableRanges = availability.unavailableRanges().stream()
			.map(range -> new AccommodationResponse.UnavailableDateRange(
				range.startInclusive(), range.endExclusive()))
			.toList();

		return new AccommodationResponse.Availability(
			availability.startInclusive(),
			availability.endExclusive(),
			unavailableRanges
		);
	}

	@Transactional(readOnly = true)
	public AccommodationResponse.HostAccommodationInfos findMyAccommodations(
		Long hostId,
		CursorRequest.CursorPageRequest cursorRequest,
		AccommodationStatus status
	) {
		Slice<HostAccommodationProjection> accommodationSlice = accommodationRepository.findMyAccommodationsByHostIdWithCursor(
			hostId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			status,
			PageRequest.of(0, cursorRequest.size())
		);

		List<HostAccommodationProjection> accommodations = accommodationSlice.getContent();
		if (accommodations.isEmpty()) {
			CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
				Collections.emptyList(), false, acc -> 0L, acc -> null
			);
			return AccommodationResponse.HostAccommodationInfos.builder()
				.accommodations(Collections.emptyList())
				.pageInfo(pageInfo)
				.build();
		}

		List<AccommodationResponse.HostAccommodationInfo> accommodationInfos = accommodations.stream()
			.map(AccommodationResponse.HostAccommodationInfo::from)
			.toList();

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			accommodations,
			accommodationSlice.hasNext(),
			HostAccommodationProjection::id,
			HostAccommodationProjection::createdAt
		);

		return AccommodationResponse.HostAccommodationInfos.from(accommodationInfos, pageInfo);
	}

	@Transactional(readOnly = true)
	public AccommodationResponse.HostDetail findHostAccommodationDetail(Long accommodationId, Long hostId) {
		HostAccommodationDetailProjection accommodation = accommodationRepository.findWithDetailsByIdAndHostId(accommodationId, hostId)
			.orElseThrow(AccommodationNotFoundException::new);

		List<AmenityResponse.AmenityInfo> amenityInfos = accommodationDetailReader.loadAmenities(accommodationId).stream()
			.map(amenity -> new AmenityResponse.AmenityInfo(amenity.type(), amenity.count()))
			.toList();
		List<ImageResponse.ImageInfo> imageInfos = accommodationDetailReader.loadImages(accommodationId).stream()
			.map(ImageResponse.ImageInfo::from)
			.toList();

		return AccommodationResponse.HostDetail.from(
			accommodation,
			amenityInfos,
			imageInfos
		);
	}

}
