package kr.kro.airbob.domain.accommodation.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.util.CursorPageInfoCreator;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.AccommodationDetailProjection;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.policy.BookingWindow;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccommodationQueryService {

	private final AccommodationReviewSummaryRepository reviewSummaryRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;
	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final AccommodationImageRepository accommodationImageRepository;
	private final AccommodationRepository accommodationRepository;
	private final ReservationRepository reservationRepository;
	private final CursorPageInfoCreator cursorPageInfoCreator;
	private final BookingWindowProvider bookingWindowProvider;

	@Transactional(readOnly = true)
	public AccommodationResponse.DetailInfo findAccommodation(Long accommodationId, Long viewerId) {
		AccommodationDetailProjection detailProjection = accommodationRepository
			.findWithDetailsByAccommodationIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);
		Accommodation accommodation = detailProjection.accommodation();

		List<AmenityResponse.AmenityInfo> amenityInfos = getAmenities(accommodationId);
		List<ImageResponse.ImageInfo> imageInfos = getImageUrls(accommodationId);

		BookingWindow bookingWindow = bookingWindowProvider.currentFor(accommodation.getTimeZoneId());
		LocalDate bookingWindowStart = bookingWindow.startInclusive();
		LocalDate bookingWindowEndExclusive = bookingWindow.endExclusive();
		List<AccommodationResponse.UnavailableDateRange> unavailableRanges = getUnavailableRanges(
			accommodationId, bookingWindowStart, bookingWindowEndExclusive);

		Boolean isInWishlist = checkWishlistStatus(accommodationId, viewerId);
		ReviewResponse.ReviewSummary reviewSummary = new ReviewResponse.ReviewSummary(
			Objects.requireNonNullElse(detailProjection.totalReviewCount(), 0),
			Objects.requireNonNullElse(detailProjection.averageRating(), BigDecimal.ZERO)
		);

		return AccommodationResponse.DetailInfo.from(
			accommodation, bookingWindowStart, bookingWindowEndExclusive, unavailableRanges, isInWishlist,
			amenityInfos, imageInfos, reviewSummary);
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

		List<AmenityResponse.AmenityInfo> amenityInfos = getAmenities(accommodationId);
		List<ImageResponse.ImageInfo> imageInfos = getImageUrls(accommodationId);
		ReviewResponse.ReviewSummary reviewSummary = getReviewSummary(accommodationId);

		return AccommodationResponse.HostDetail.from(
			accommodation,
			amenityInfos,
			imageInfos,
			reviewSummary
		);
	}

	private List<AmenityResponse.AmenityInfo> getAmenities(Long accommodationId) {
		return accommodationAmenityRepository.findAllByAccommodationId(accommodationId)
			.stream()
			.map(AmenityResponse.AmenityInfo::from)
			.toList();
	}

	private List<ImageResponse.ImageInfo> getImageUrls(Long accommodationId) {
		return accommodationImageRepository.findByAccommodationIdOrderByIdAsc(accommodationId)
			.stream()
			.map(ImageResponse.ImageInfo::from)
			.toList();
	}

	private ReviewResponse.ReviewSummary getReviewSummary(Long accommodationId) {
		Optional<AccommodationReviewSummary> summaryOpt = reviewSummaryRepository.findByAccommodationId(
			accommodationId);
		return ReviewResponse.ReviewSummary.of(summaryOpt.orElse(null));
	}

	private List<AccommodationResponse.UnavailableDateRange> getUnavailableRanges(
		Long accommodationId,
		LocalDate windowStart,
		LocalDate windowEndExclusive
	) {
		List<ReservationDateRange> reservationRanges = reservationRepository
			.findActiveReservationRangesByAccommodationId(
				accommodationId,
				windowStart,
				windowEndExclusive);

		List<AccommodationResponse.UnavailableDateRange> clippedRanges = reservationRanges.stream()
			.map(range -> clipUnavailableRange(range, windowStart, windowEndExclusive))
			.filter(range -> range.startDate().isBefore(range.endDateExclusive()))
			.sorted(Comparator
				.comparing(AccommodationResponse.UnavailableDateRange::startDate)
				.thenComparing(AccommodationResponse.UnavailableDateRange::endDateExclusive))
			.toList();

		return mergeUnavailableRanges(clippedRanges);
	}

	private AccommodationResponse.UnavailableDateRange clipUnavailableRange(
		ReservationDateRange range,
		LocalDate windowStart,
		LocalDate windowEndExclusive
	) {
		LocalDate startDate = range.checkIn();
		LocalDate endDateExclusive = range.checkOut();

		if (startDate.isBefore(windowStart)) {
			startDate = windowStart;
		}
		if (endDateExclusive.isAfter(windowEndExclusive)) {
			endDateExclusive = windowEndExclusive;
		}

		return new AccommodationResponse.UnavailableDateRange(startDate, endDateExclusive);
	}

	private List<AccommodationResponse.UnavailableDateRange> mergeUnavailableRanges(
		List<AccommodationResponse.UnavailableDateRange> ranges
	) {
		if (ranges.isEmpty()) {
			return List.of();
		}

		List<AccommodationResponse.UnavailableDateRange> mergedRanges = new ArrayList<>();
		AccommodationResponse.UnavailableDateRange current = ranges.getFirst();

		for (int index = 1; index < ranges.size(); index++) {
			AccommodationResponse.UnavailableDateRange next = ranges.get(index);

			if (!next.startDate().isAfter(current.endDateExclusive())) {
				LocalDate mergedEnd = next.endDateExclusive().isAfter(current.endDateExclusive())
					? next.endDateExclusive()
					: current.endDateExclusive();
				current = new AccommodationResponse.UnavailableDateRange(current.startDate(), mergedEnd);
				continue;
			}

			mergedRanges.add(current);
			current = next;
		}

		mergedRanges.add(current);
		return List.copyOf(mergedRanges);
	}

	private Boolean checkWishlistStatus(Long accommodationId, Long viewerId) {
		if (viewerId == null) {
			return false;
		}

		return wishlistAccommodationRepository.existsByWishlist_Member_IdAndAccommodation_Id(
			viewerId, accommodationId);
	}
}
