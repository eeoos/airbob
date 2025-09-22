package kr.kro.airbob.search.service;

import java.time.LocalDate;
import java.util.List;

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

	public AccommodationDocument buildAccommodationDocument(Long accommodationId) {
		Accommodation accommodation = accommodationRepository.findById(accommodationId)
			.orElseThrow(AccommodationNotFoundException::new);

		// 편의시설
		List<String> amenityTypes = getAccommodationAmenities(accommodationId);

		// 이미지
		List<String> imageUrls = getAccommodationImages(accommodationId, accommodation.getThumbnailUrl());

		// 예약 날짜
		List<LocalDate> reservedDates = getReservedDates(accommodationId);

		// 리뷰 요약
		AccommodationReviewSummary reviewSummary = getReviewSummary(accommodationId);

		return AccommodationDocument.builder()
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

	private AccommodationReviewSummary getReviewSummary(Long accommodationId) {
		return reviewSummaryRepository.findByAccommodationId(accommodationId)
			.orElse(null);
	}

	private List<String> getAccommodationImages(Long accommodationId, String thumbnailUrl) {
		List<String> imageUrls = imageRepository.findImagesByAccommodationId(accommodationId)
			.stream()
			.map(AccommodationImage::getImageUrl)
			.toList();

		// 이미지가 없는 경우 썸네일 조회
		if (imageUrls.isEmpty() && thumbnailUrl != null) {
			imageUrls = List.of(thumbnailUrl);
		}
		return imageUrls;
	}

	private List<String> getAccommodationAmenities(Long accommodationId) {
		return amenityRepository.findAllByAccommodationId(accommodationId)
			.stream()
			.map(AccommodationAmenity::getAmenity)
			.map(amenity -> amenity.getName().name())
			.distinct()
			.toList();
	}

	private List<LocalDate> getReservedDates(Long accommodationId) {
		return reservationRepository
			.findFutureCompletedReservations(accommodationId)
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
