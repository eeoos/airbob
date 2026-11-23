package kr.kro.airbob.domain.accommodation.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.domain.image.StoredImage;
import kr.kro.airbob.domain.accommodation.AccommodationDetailSnapshot;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.accommodation.repository.projection.PublicAccommodationDetailProjection;
import kr.kro.airbob.domain.accommodation.port.AccommodationWishlistReader;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationDetailReader {

	private final AccommodationRepository accommodationRepository;
	private final AccommodationAmenityRepository accommodationAmenityRepository;
	private final AccommodationImageRepository accommodationImageRepository;
	private final AccommodationWishlistReader wishlistReader;

	@Transactional(readOnly = true)
	public AccommodationDetailSnapshot load(Long accommodationId) {
		PublicAccommodationDetailProjection projection = accommodationRepository
			.findWithDetailsByAccommodationIdAndStatus(accommodationId, AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);
		return toSnapshot(accommodationId, projection);
	}

	private AccommodationDetailSnapshot toSnapshot(Long accommodationId,
		PublicAccommodationDetailProjection projection) {
		List<AccommodationDetailSnapshot.Amenity> amenities = loadAmenities(accommodationId);
		List<StoredImage> images = loadImages(accommodationId);

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
			new AccommodationDetailSnapshot.AddressSummary(
				projection.country(), projection.state(), projection.city(), projection.district()),
			new AccommodationDetailSnapshot.Coordinate(projection.latitude(), projection.longitude()),
			new AccommodationDetailSnapshot.Host(projection.hostId(), projection.hostNickname(), projection.hostThumbnailImageUrl()),
			new AccommodationDetailSnapshot.Policy(projection.maxOccupancy(), projection.infantOccupancy(), projection.petOccupancy()),
			amenities,
			images,
			AccommodationDetailSnapshot.ReviewSummary.of(projection.totalReviewCount(), projection.averageRating())
		);
	}

	@Transactional(readOnly = true)
	public boolean isInWishlist(Long accommodationId, Long viewerId) {
		return wishlistReader.isInWishlist(accommodationId, viewerId);
	}

	List<AccommodationDetailSnapshot.Amenity> loadAmenities(Long accommodationId) {
		return accommodationAmenityRepository.findDetailAmenitiesByAccommodationId(accommodationId)
			.stream()
			.map(amenity -> new AccommodationDetailSnapshot.Amenity(amenity.type(), amenity.count()))
			.toList();
	}

	List<StoredImage> loadImages(Long accommodationId) {
		return accommodationImageRepository.findDetailImagesByAccommodationIdOrderByIdAsc(accommodationId)
			.stream()
			.map(image -> new StoredImage(image.id(), image.imageUrl()))
			.toList();
	}
}
