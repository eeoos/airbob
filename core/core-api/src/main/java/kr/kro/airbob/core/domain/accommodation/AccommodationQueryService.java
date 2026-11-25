package kr.kro.airbob.core.domain.accommodation;

import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationAvailability;
import kr.kro.airbob.core.domain.accommodation.port.AccommodationAvailabilityReader;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.cursor.dto.CursorResponse;
import kr.kro.airbob.core.support.cursor.util.CursorPageInfoCreator;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccommodationQueryService {

	private final AccommodationReader accommodationReader;
	private final AccommodationAvailabilityReader availabilityReader;
	private final CursorPageInfoCreator cursorPageInfoCreator;
	private final AccommodationDetailReader accommodationDetailReader;
	private final AccommodationDetailCache accommodationDetailCache;

	public PublicAccommodationDetail findAccommodation(Long accommodationId, Long viewerId) {
		AccommodationDetailSnapshot snapshot = accommodationDetailCache.getOrLoad(
			accommodationId,
			() -> accommodationDetailReader.load(accommodationId));
		boolean isInWishlist = viewerId != null
			&& accommodationDetailReader.isInWishlist(accommodationId, viewerId);

		return new PublicAccommodationDetail(snapshot, isInWishlist);
	}

	@Transactional(readOnly = true)
	public AccommodationAvailability findAccommodationAvailability(Long accommodationId) {
		String timeZoneId = accommodationReader.readPublishedTimeZoneId(accommodationId);
		return availabilityReader.readAvailability(accommodationId, timeZoneId);
	}

	@Transactional(readOnly = true)
	public HostAccommodationPage findMyAccommodations(
		Long hostId,
		CursorRequest.CursorPageRequest cursorRequest,
		AccommodationStatus status) {
		Slice<HostAccommodationProjection> accommodationSlice = accommodationReader.readHostPage(hostId, cursorRequest,
			status);

		List<HostAccommodationProjection> accommodations = accommodationSlice.getContent();
		if (accommodations.isEmpty()) {
			CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
				Collections.emptyList(), false, acc -> 0L, acc -> null);
			return new HostAccommodationPage(Collections.emptyList(), pageInfo);
		}

		CursorResponse.PageInfo pageInfo = cursorPageInfoCreator.createPageInfo(
			accommodations,
			accommodationSlice.hasNext(),
			HostAccommodationProjection::id,
			HostAccommodationProjection::createdAt);

		return new HostAccommodationPage(accommodations, pageInfo);
	}

	@Transactional(readOnly = true)
	public HostAccommodationDetail findHostAccommodationDetail(Long accommodationId, Long hostId) {
		HostAccommodationDetailProjection accommodation = accommodationReader.readHostDetail(accommodationId, hostId);

		return new HostAccommodationDetail(
			accommodation,
			accommodationDetailReader.loadAmenities(accommodationId),
			accommodationDetailReader.loadImages(accommodationId));
	}

}
