package kr.kro.airbob.search.service;

import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationAmenity;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
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
			.currency(accommodation.getCurrency())
			.type(accommodation.getType().name())
			.status(accommodation.getStatus().name())
			.createdAt(accommodation.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant())
			.location(AccommodationDocument.Location.builder()
				.lat(accommodation.getAddress().getLatitude())
				.lon(accommodation.getAddress().getLongitude())
				.build())
			.country(accommodation.getAddress().getCountry())
			.state(accommodation.getAddress().getState())
			.city(accommodation.getAddress().getCity())
			.district(accommodation.getAddress().getDistrict())
			.street(accommodation.getAddress().getStreet())
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

	public AccommodationDocument build(Accommodation accommodation,
		AccommodationReviewSummary summary,
		List<AccommodationAmenity> amenities) {
		if (accommodation == null) {
			return null;
		}

		AccommodationDocument.AccommodationDocumentBuilder builder = AccommodationDocument.builder();

		builder.id(accommodation.getAccommodationUid().toString());

		builder.accommodationId(accommodation.getId())
			.name(accommodation.getName())
			.description(accommodation.getDescription())
			.basePrice(accommodation.getBasePrice())
			.currency(accommodation.getCurrency())
			.thumbnailUrl(accommodation.getThumbnailUrl())
			.type(accommodation.getType().name())
			.status(accommodation.getStatus().name())
			.createdAt(accommodation.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());

		Address address = accommodation.getAddress();
		if (address != null) {

			builder.country(address.getCountry())
				.state(address.getState())
				.city(address.getCity())
				.district(address.getDistrict())
				.street(address.getStreet())
				.postalCode(address.getPostalCode())
				.location(AccommodationDocument.Location.builder()
					.lat(address.getLatitude())
					.lon(address.getLongitude())
					.build());
		}

		OccupancyPolicy policy = accommodation.getOccupancyPolicy();
		if (policy != null) {
			builder.maxGuests(policy.getMaxOccupancy())
				.maxInfants(policy.getInfantOccupancy())
				.maxPets(policy.getPetOccupancy());
		} else {
			// ES에서 null 필터링 문제를 피하기 위해 기본값 0 설정
			builder.maxGuests(0).maxInfants(0).maxPets(0);
		}

		List<String> amenityNames;
		if (amenities != null && !amenities.isEmpty()) {
			amenityNames = amenities.stream()
				.map(am -> am.getAmenity().getName().name())
				.collect(Collectors.toList());
		} else {
			amenityNames = Collections.emptyList();
		}
		builder.amenityTypes(amenityNames);

		if (summary != null) {
			builder.averageRating(summary.getAverageRating() != null ? summary.getAverageRating().doubleValue() : 0.0)
				.reviewCount(summary.getTotalReviewCount() != null ? summary.getTotalReviewCount() : 0);
		} else {
			// 리뷰가 없는 숙소(summary=null)는 0점으로 초기화
			builder.averageRating(0.0)
				.reviewCount(0);
		}

		return builder.build();
	}
}
