package kr.kro.airbob.domain.accommodation.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.dto.PolicyResponse;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.AccommodationDetailProjection;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.wishlist.repository.WishlistAccommodationRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationDetailReader {

	private final AccommodationRepository accommodationRepository;
	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final AccommodationImageRepository accommodationImageRepository;
	private final WishlistAccommodationRepository wishlistAccommodationRepository;

	@Transactional(readOnly = true)
	public AccommodationDetailSnapshot load(Long accommodationId) {
		AccommodationDetailProjection projection = accommodationRepository
			.findWithDetailsByAccommodationIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);
		Accommodation accommodation = projection.accommodation();
		List<AmenityResponse.AmenityInfo> amenities = loadAmenities(accommodationId);
		List<ImageResponse.ImageInfo> images = loadImages(accommodationId);
		ReviewResponse.ReviewSummary reviewSummary = ReviewResponse.ReviewSummary.of(
			projection.totalReviewCount(), projection.averageRating());

		return new AccommodationDetailSnapshot(
			accommodation.getId(),
			accommodation.getName(),
			accommodation.getDescription(),
			accommodation.getType(),
			accommodation.getBasePrice(),
			accommodation.getCurrency(),
			accommodation.getCheckInTime(),
			accommodation.getCheckOutTime(),
			accommodation.getTimeZoneId(),
			AddressResponse.AddressSummaryInfo.from(accommodation.getAddress()),
			AddressResponse.Coordinate.from(accommodation.getAddress()),
			MemberResponse.MemberInfo.from(accommodation.getMember()),
			PolicyResponse.PolicyInfo.from(accommodation.getOccupancyPolicy()),
			amenities,
			images,
			reviewSummary
		);
	}

	@Transactional(readOnly = true)
	public boolean isInWishlist(Long accommodationId, Long viewerId) {
		return wishlistAccommodationRepository.existsByWishlist_Member_IdAndAccommodation_Id(
			viewerId, accommodationId);
	}

	List<AmenityResponse.AmenityInfo> loadAmenities(Long accommodationId) {
		return accommodationAmenityRepository.findAllByAccommodationId(accommodationId)
			.stream()
			.map(AmenityResponse.AmenityInfo::from)
			.toList();
	}

	List<ImageResponse.ImageInfo> loadImages(Long accommodationId) {
		return accommodationImageRepository.findByAccommodationIdOrderByIdAsc(accommodationId)
			.stream()
			.map(ImageResponse.ImageInfo::from)
			.toList();
	}
}
