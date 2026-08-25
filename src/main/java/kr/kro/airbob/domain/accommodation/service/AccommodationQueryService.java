package kr.kro.airbob.domain.accommodation.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.cache.AccommodationDetailCache;
import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.reservation.inventory.ReservationInventoryService;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccommodationQueryService {

	private final AccommodationReviewSummaryRepository reviewSummaryRepository;
	private final AccommodationRepository accommodationRepository;
	private final ReservationInventoryService inventoryService;
	private final CursorPageInfoCreator cursorPageInfoCreator;
	private final BookingWindowProvider bookingWindowProvider;
	private final AccommodationDetailReader accommodationDetailReader;
	private final AccommodationDetailCache accommodationDetailCache;
	private final Clock clock;

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
		BookingWindow bookingWindow = bookingWindowProvider.currentFor(timeZoneId);
		Instant queriedAt = clock.instant();
		LocalDate bookingWindowStart = bookingWindow.startInclusive();
		LocalDate bookingWindowEndExclusive = bookingWindow.endExclusive();
		List<AccommodationResponse.UnavailableDateRange> unavailableRanges = inventoryService
			.findUnavailableRangesSnapshot(
				accommodationId,
				bookingWindowStart,
				bookingWindowEndExclusive,
				queriedAt
			)
			.stream()
			.map(range -> new AccommodationResponse.UnavailableDateRange(
				range.startInclusive(), range.endExclusive()))
			.toList();

		return new AccommodationResponse.Availability(
			bookingWindowStart,
			bookingWindowEndExclusive,
			unavailableRanges
		);
	}

	@Transactional(readOnly = true)
	public AccommodationResponse.HostAccommodationInfos findMyAccommodations(
		Long hostId,
		CursorRequest.CursorPageRequest cursorRequest,
		AccommodationStatus status
	) {
		Slice<Accommodation> accommodationSlice = accommodationRepository.findMyAccommodationsByHostIdWithCursor(
			hostId,
			cursorRequest.lastId(),
			cursorRequest.lastCreatedAt(),
			status,
			PageRequest.of(0, cursorRequest.size())
		);

		List<Accommodation> accommodations = accommodationSlice.getContent();
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
			Accommodation::getId,
			Accommodation::getCreatedAt
		);

		return AccommodationResponse.HostAccommodationInfos.from(accommodationInfos, pageInfo);
	}

	@Transactional(readOnly = true)
	public AccommodationResponse.HostDetail findHostAccommodationDetail(Long accommodationId, Long hostId) {
		Accommodation accommodation = accommodationRepository.findWithDetailsByIdAndHostId(accommodationId, hostId)
			.orElseThrow(AccommodationNotFoundException::new);

		List<AmenityResponse.AmenityInfo> amenityInfos = accommodationDetailReader.loadAmenities(accommodationId);
		List<ImageResponse.ImageInfo> imageInfos = accommodationDetailReader.loadImages(accommodationId);
		ReviewResponse.ReviewSummary reviewSummary = getReviewSummary(accommodationId);

		return AccommodationResponse.HostDetail.from(
			accommodation,
			amenityInfos,
			imageInfos,
			reviewSummary
		);
	}

	private ReviewResponse.ReviewSummary getReviewSummary(Long accommodationId) {
		Optional<AccommodationReviewSummary> summaryOpt = reviewSummaryRepository.findByAccommodationId(
			accommodationId);
		return ReviewResponse.ReviewSummary.of(summaryOpt.orElse(null));
	}

}
