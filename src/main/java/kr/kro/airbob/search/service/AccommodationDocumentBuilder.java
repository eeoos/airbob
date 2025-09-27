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
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.AccommodationReviewSummary;
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

	public AccommodationDocument buildAccommodationDocument(String accommodationUid) {
		Accommodation accommodation = accommodationRepository.findByAccommodationUid(UUID.fromString(accommodationUid))
			.orElseThrow(AccommodationNotFoundException::new);

		// 편의시설
		List<String> amenityTypes = getAccommodationAmenities(accommodationUid);

		// 이미지
		List<String> imageUrls = getAccommodationImages(accommodationUid, accommodation.getThumbnailUrl());

		// 예약 날짜
		List<LocalDate> reservedDates = getReservedDates(accommodationUid);

		// 리뷰 요약
		AccommodationReviewSummary reviewSummary = getReviewSummary(accommodationUid);

		return AccommodationDocument.builder()
			.id(accommodationUid)
			.accommodationId(accommodation.getId())
			.name(accommodation.getName())
			.description(accommodation.getDescription())
			.basePrice(accommodation.getBasePrice())
			.type(accommodation.getType().name())
			.createdAt(accommodation.getCreatedAt())
			// 위치
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
			// 수용 인원
			.maxOccupancy(accommodation.getOccupancyPolicy().getMaxOccupancy())
			.adultOccupancy(accommodation.getOccupancyPolicy().getAdultOccupancy())
			.childOccupancy(accommodation.getOccupancyPolicy().getChildOccupancy())
			.infantOccupancy(accommodation.getOccupancyPolicy().getInfantOccupancy())
			.petOccupancy(accommodation.getOccupancyPolicy().getPetOccupancy())
			// 편의 시설
			.amenityTypes(amenityTypes)
			// 이미지
			.imageUrls(imageUrls)
			// 예약 날짜
			.reservedDates(reservedDates)
			// 리뷰 요약
			.averageRating(reviewSummary != null ? reviewSummary.getAverageRating().doubleValue() : null)
			.reviewCount(reviewSummary != null ? reviewSummary.getTotalReviewCount() : null)
			// 호스트
			.hostId(accommodation.getMember().getId())
			.hostNickname(accommodation.getMember().getNickname())
			.build();
	}

	private AccommodationReviewSummary getReviewSummary(String accommodationUid) {
		return reviewSummaryRepository.findByAccommodation_AccommodationUid(UUID.fromString(accommodationUid))
			.orElse(null);
	}

	private List<String> getAccommodationImages(String accommodationUid, String thumbnailUrl) {
		List<String> imageUrls = imageRepository.findImagesByAccommodationUid(UUID.fromString(accommodationUid))
			.stream()
			.map(AccommodationImage::getImageUrl)
			.toList();

		// 이미지가 없는 경우 썸네일 조회
		if (imageUrls.isEmpty() && thumbnailUrl != null) {
			imageUrls = List.of(thumbnailUrl);
		}
		return imageUrls;
	}

	private List<String> getAccommodationAmenities(String accommodationUid) {
		return amenityRepository.findAllByAccommodation_AccommodationUid(UUID.fromString(accommodationUid))
			.stream()
			.map(AccommodationAmenity::getAmenity)
			.map(amenity -> amenity.getName().name())
			.distinct()
			.toList();
	}

	private List<LocalDate> getReservedDates(String accommodationUid) {
		return reservationRepository
			.findFutureCompletedReservations(UUID.fromString(accommodationUid))
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
}
