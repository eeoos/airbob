package kr.kro.airbob.search.service;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.accommodation.Accommodation;
import kr.kro.airbob.core.domain.accommodation.AccommodationAmenity;
import kr.kro.airbob.core.domain.accommodation.AccommodationAmenityReader;
import kr.kro.airbob.core.domain.accommodation.AccommodationNotFoundException;
import kr.kro.airbob.core.domain.accommodation.AccommodationReader;
import kr.kro.airbob.core.domain.accommodation.AccommodationReviewSummary;
import kr.kro.airbob.core.domain.accommodation.AccommodationReviewSummaryReader;
import kr.kro.airbob.core.domain.accommodation.AccommodationStatus;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationIndexingWindow;
import kr.kro.airbob.domain.reservation.service.ReservationReader;
import kr.kro.airbob.search.document.AccommodationDocument;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationDocumentBuilder {

	private final AccommodationReader accommodationReader;
	private final AccommodationAmenityReader amenityReader;
	private final ReservationReader reservationReader;
	private final AccommodationReviewSummaryReader reviewSummaryReader;
	private final BookingWindowProvider bookingWindowProvider;

	public AccommodationDocument buildAccommodationDocument(UUID accommodationUid) {
		Accommodation accommodation = accommodationReader.findWithDetailsByAccommodationUid(accommodationUid)
			.orElseThrow(AccommodationNotFoundException::new);
		if (accommodation.getStatus() != AccommodationStatus.PUBLISHED) {
			return AccommodationDocument.builder()
				.id(accommodationUid.toString())
				.status(accommodation.getStatus().name())
				.build();
		}

		List<String> amenityTypes = getAccommodationAmenities(accommodationUid);
		List<AccommodationDocument.DateRange> reservationRanges = getReservationRanges(accommodation.getId());
		AccommodationReviewSummary reviewSummary = getReviewSummary(accommodationUid);

		return AccommodationDocument.builder()
			.id(accommodation.getAccommodationUid().toString())
			.accommodationId(accommodation.getId())
			.name(accommodation.getName())
			.description(accommodation.getDescription())
			.basePrice(accommodation.getBasePrice())
			.currency(accommodation.getCurrency())
			.type(accommodation.getType())
			.status(accommodation.getStatus().name())
			.timeZoneId(accommodation.getTimeZoneId())
			.createdAt(accommodation.getCreatedAt().toInstant(ZoneOffset.UTC))
			.location(AccommodationDocument.Location.builder()
				.lat(accommodation.getAddress().getLatitude())
				.lon(accommodation.getAddress().getLongitude())
				.build())
			.country(accommodation.getAddress().getCountry())
			.state(accommodation.getAddress().getState())
			.city(accommodation.getAddress().getCity())
			.district(accommodation.getAddress().getDistrict())
			.street(accommodation.getAddress().getStreet())
			.addressDetail(accommodation.getAddress().getDetail())
			.postalCode(accommodation.getAddress().getPostalCode())
			.maxGuests(accommodation.getOccupancyPolicy().getMaxOccupancy())
			.maxInfants(accommodation.getOccupancyPolicy().getInfantOccupancy())
			.maxPets(accommodation.getOccupancyPolicy().getPetOccupancy())
			.amenityTypes(amenityTypes)
			.thumbnailUrl(accommodation.getThumbnailUrl())
			.reservationRanges(reservationRanges)
			.averageRating(reviewSummary != null && reviewSummary.getAverageRating() != null
				? reviewSummary.getAverageRating().doubleValue()
				: 0.0)
			.reviewCount(reviewSummary != null && reviewSummary.getTotalReviewCount() != null
				? reviewSummary.getTotalReviewCount()
				: 0)
			.build();
	}

	private AccommodationReviewSummary getReviewSummary(UUID accommodationUid) {
		return reviewSummaryReader.findByAccommodationUid(accommodationUid)
			.orElse(null);
	}

	private List<AccommodationDocument.DateRange> getReservationRanges(Long accommodationId) {
		ReservationIndexingWindow window = bookingWindowProvider.currentIndexingWindow();
		return reservationReader
			.readActiveRanges(
				accommodationId,
				window.startInclusive(),
				window.endExclusive()
			)
			.stream()
			.map(dateRange -> AccommodationDocument.DateRange.builder()
				.gte(dateRange.checkIn()) // Check-in (gte)
				.lt(dateRange.checkOut()) // Check-out (lt)
				.build()
			)
			.toList();
	}

	private List<String> getAccommodationAmenities(UUID accommodationUid) {
		return amenityReader.findAllByAccommodationUid(accommodationUid)
			.stream()
			.map(AccommodationAmenity::getAmenityCode)
			.distinct()
			.toList();
	}

}
