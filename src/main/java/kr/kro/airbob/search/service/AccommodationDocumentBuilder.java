package kr.kro.airbob.search.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationImageRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.image.AccommodationImage;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.search.document.AccommodationDocument;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationDocumentBuilder {

	private final AccommodationRepository accommodationRepository;
	private final AccommodationAmenityRepository amenityRepository;
	private final ReservationRepository reservationRepository;
	private final AccommodationImageRepository imageRepository;
	private final AccommodationReviewSummaryRepository reviewSummaryRepository;

	public AccommodationDocument buildAccommodationDocument(String accommodationUidStr) {
		UUID accommodationUid = UUID.fromString(accommodationUidStr);

		Accommodation accommodation = accommodationRepository.findWithDetailsByAccommodationUid(accommodationUid)
			.orElseThrow(AccommodationNotFoundException::new);

		List<String> amenityTypes = getAccommodationAmenities(accommodationUid);
		List<String> imageUrls = getAccommodationImages(accommodationUid, accommodation.getThumbnailUrl());
		List<LocalDate> reservedDates = getReservedDates(accommodationUid);
		AccommodationReviewSummary reviewSummary = getReviewSummary(accommodationUid);

		return AccommodationDocument.builder()
			.id(accommodation.getAccommodationUid().toString())
			.accommodationId(accommodation.getId())
			.name(accommodation.getName())
			.description(accommodation.getDescription())
			.basePrice(accommodation.getBasePrice())
			.type(accommodation.getType().name())
			.createdAt(accommodation.getCreatedAt())
			.location(AccommodationDocument.Location.builder()
				.lat(accommodation.getAddress().getLatitude())
				.lon(accommodation.getAddress().getLongitude())
				.build())
			.country(accommodation.getAddress().getCountry())
			.city(accommodation.getAddress().getCity())
			.district(accommodation.getAddress().getDistrict())
			.street(accommodation.getAddress().getStreet())
			.addressDetail(accommodation.getAddress().getDetail())
			.postalCode(accommodation.getAddress().getPostalCode())
			.maxOccupancy(accommodation.getOccupancyPolicy().getMaxOccupancy())
			.adultOccupancy(accommodation.getOccupancyPolicy().getAdultOccupancy())
			.childOccupancy(accommodation.getOccupancyPolicy().getChildOccupancy())
			.infantOccupancy(accommodation.getOccupancyPolicy().getInfantOccupancy())
			.petOccupancy(accommodation.getOccupancyPolicy().getPetOccupancy())
			.hostId(accommodation.getMember().getId())
			.hostNickname(accommodation.getMember().getNickname())
			.amenityTypes(amenityTypes)
			.imageUrls(imageUrls)
			.reservedDates(reservedDates)
			.averageRating(reviewSummary != null ? reviewSummary.getAverageRating().doubleValue() : null)
			.reviewCount(reviewSummary != null ? reviewSummary.getTotalReviewCount() : null)
			.build();
	}

	private AccommodationReviewSummary getReviewSummary(UUID accommodationUid) {
		return reviewSummaryRepository.findByAccommodation_AccommodationUid(accommodationUid)
			.orElse(null);
	}

	private List<LocalDate> getReservedDates(UUID accommodationUid) {
		return reservationRepository
			.findFutureCompletedReservations(accommodationUid)
			.stream()
			.flatMap(reservation -> {
				LocalDate checkInDate = reservation.getCheckIn().toLocalDate();
				LocalDate checkOutDate = reservation.getCheckOut().toLocalDate();
				// checkOutDate는 숙박일에 포함되지 않으므로 datesUntil 사용
				return checkInDate.datesUntil(checkOutDate);
			})
			.distinct()
			.sorted()
			.toList();
	}

	private List<String> getAccommodationImages(UUID accommodationUid, String thumbnailUrl) {
		List<String> imageUrls = imageRepository.findImagesByAccommodationUid(accommodationUid)
			.stream()
			.map(AccommodationImage::getImageUrl)
			.toList();

		// 이미지가 없는 경우 썸네일 조회
		if (imageUrls.isEmpty() && thumbnailUrl != null) {
			imageUrls = List.of(thumbnailUrl);
		}
		return imageUrls;
	}

	private List<String> getAccommodationAmenities(UUID accommodationUid) {
		return amenityRepository.findAllByAccommodation_AccommodationUid(accommodationUid)
			.stream()
			.map(AccommodationAmenity::getAmenity)
			.map(amenity -> amenity.getName().name())
			.distinct()
			.toList();
	}
}
