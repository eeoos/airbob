package kr.kro.airbob.domain.accommodation.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.accommodation.dto.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.accommodation.dto.AmenityResponse;
import kr.kro.airbob.domain.accommodation.dto.PolicyResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.PublicAccommodationDetailProjection;
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
		PublicAccommodationDetailProjection projection = accommodationRepository
			.findWithDetailsByAccommodationIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);
		List<AmenityResponse.AmenityInfo> amenities = loadAmenities(accommodationId);
		List<ImageResponse.ImageInfo> images = loadImages(accommodationId);
		ReviewResponse.ReviewSummary reviewSummary = ReviewResponse.ReviewSummary.of(
			projection.totalReviewCount(), projection.averageRating());

		return new AccommodationDetailSnapshot(
			projection.id(),
			projection.name(),
			projection.description(),
			projection.type(),
			projection.basePrice(),
			projection.currency(),
			projection.checkInTime(),
			projection.checkOutTime(),
			projection.timeZoneId(),
			new AddressResponse.AddressSummaryInfo(
				projection.country(), projection.state(), projection.city(), projection.district()),
			new AddressResponse.Coordinate(projection.latitude(), projection.longitude()),
			new MemberResponse.MemberInfo(projection.hostId(), projection.hostNickname(), projection.hostThumbnailImageUrl()),
			new PolicyResponse.PolicyInfo(projection.maxOccupancy(), projection.infantOccupancy(), projection.petOccupancy()),
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
		return accommodationAmenityRepository.findDetailAmenitiesByAccommodationId(accommodationId)
			.stream()
			.map(amenity -> new AmenityResponse.AmenityInfo(amenity.type(), amenity.count()))
			.toList();
	}

	List<ImageResponse.ImageInfo> loadImages(Long accommodationId) {
		return accommodationImageRepository.findDetailImagesByAccommodationIdOrderByIdAsc(accommodationId)
			.stream()
			.map(image -> new ImageResponse.ImageInfo(image.id(), image.imageUrl()))
			.toList();
	}
}
