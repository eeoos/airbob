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
	private final AccommodationReviewSummaryRepository reviewSummaryRepository;

	public AccommodationDocument buildAccommodationDocument(String accommodationUidStr) {
		UUID accommodationUid = UUID.fromString(accommodationUidStr);

		Accommodation accommodation = accommodationRepository.findWithDetailsByAccommodationUid(accommodationUid)
			.orElseThrow(AccommodationNotFoundException::new);

		List<String> amenityTypes = getAccommodationAmenities(accommodationUid);
		// List<String> imageUrls = getAccommodationImages(accommodationUid, accommodation.getThumbnailUrl());
		List<AccommodationDocument.DateRange> reservationRanges = getReservationRanges(accommodationUid);
		AccommodationReviewSummary reviewSummary = getReviewSummary(accommodationUid);

		return AccommodationDocument.builder()
			.id(accommodation.getAccommodationUid().toString())
			.accommodationId(accommodation.getId())
			.name(accommodation.getName())
			.description(accommodation.getDescription())
			.basePrice(accommodation.getBasePrice())
			.type(accommodation.getType().name())
			.status(accommodation.getStatus().name())
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
			.maxGuests(accommodation.getOccupancyPolicy().getMaxOccupancy())
			.maxInfants(accommodation.getOccupancyPolicy().getInfantOccupancy())
			.maxPets(accommodation.getOccupancyPolicy().getPetOccupancy())
			.hostId(accommodation.getMember().getId())
			.hostNickname(accommodation.getMember().getNickname())
			.amenityTypes(amenityTypes)
			.thumbnailUrl(accommodation.getThumbnailUrl())
			.reservationRanges(reservationRanges)
			.averageRating(reviewSummary != null ? reviewSummary.getAverageRating().doubleValue() : null)
			.reviewCount(reviewSummary != null ? reviewSummary.getTotalReviewCount() : null)
			.build();
	}

	private AccommodationReviewSummary getReviewSummary(UUID accommodationUid) {
		return reviewSummaryRepository.findByAccommodation_AccommodationUid(accommodationUid)
			.orElse(null);
	}

	private List<AccommodationDocument.DateRange> getReservationRanges(UUID accommodationUid) {
		return reservationRepository
			.findFutureCompletedReservations(accommodationUid)
			.stream()
			.map(reservation -> AccommodationDocument.DateRange.builder()
				.gte(reservation.getCheckIn().toLocalDate()) // Check-in (gte)
				.lt(reservation.getCheckOut().toLocalDate()) // Check-out (lt)
				.build()
			)
			.toList();
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
