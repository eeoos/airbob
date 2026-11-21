package kr.kro.airbob.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.entity.Address;
import kr.kro.airbob.domain.accommodation.entity.OccupancyPolicy;
import kr.kro.airbob.domain.accommodation.repository.AccommodationAmenityRepository;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationIndexingWindow;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import kr.kro.airbob.search.document.AccommodationDocument;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 검색 문서 빌더 단위 테스트")
class AccommodationDocumentBuilderTest {

	@Mock
	private AccommodationRepository accommodationRepository;

	@Mock
	private AccommodationAmenityRepository amenityRepository;

	@Mock
	private ReservationRepository reservationRepository;

	@Mock
	private AccommodationReviewSummaryRepository reviewSummaryRepository;

	@Mock
	private BookingWindowProvider bookingWindowProvider;

	@InjectMocks
	private AccommodationDocumentBuilder documentBuilder;

	@Test
	@DisplayName("검색 대상이 아닌 숙소는 부가 필드/연관 projection 없이 상태만 반환한다")
	void skipsFullProjectionForUnpublishedAccommodation() {
		UUID accommodationUid = UUID.fromString(
			"89122b09-3d6f-482d-9a44-76948db7a7c7");
		Accommodation unpublished = Accommodation.builder()
			.accommodationUid(accommodationUid)
			.status(AccommodationStatus.UNPUBLISHED)
			.build();
		when(accommodationRepository.findWithDetailsByAccommodationUid(accommodationUid))
			.thenReturn(Optional.of(unpublished));

		AccommodationDocument document =
			documentBuilder.buildAccommodationDocument(accommodationUid);

		assertThat(document.id()).isEqualTo(accommodationUid.toString());
		assertThat(document.status()).isEqualTo(AccommodationStatus.UNPUBLISHED.name());
		verifyNoInteractions(
			amenityRepository,
			reservationRepository,
			reviewSummaryRepository,
			bookingWindowProvider);
	}

	@Test
	@DisplayName("예약 범위는 숙소 UID로 조회하고 병합, 중복 제거, 정렬 없이 날짜 범위로 변환한다")
	void preserveEachReservationRangeFromUidProjection() {
		UUID accommodationUid = UUID.fromString("8df7d116-42d1-44f4-87f5-ab87295caf23");
		ReservationIndexingWindow window = new ReservationIndexingWindow(
			LocalDate.of(2026, 8, 11), LocalDate.of(2026, 11, 13));
		Accommodation accommodation = Accommodation.builder()
			.id(41L)
			.accommodationUid(accommodationUid)
			.name("Range House")
			.description("Reservation range test fixture")
			.basePrice(150_000L)
			.currency("KRW")
			.type("HOUSE")
			.status(AccommodationStatus.PUBLISHED)
			.timeZoneId("Asia/Seoul")
			.createdAt(LocalDateTime.of(2026, 8, 1, 9, 0))
			.address(Address.builder()
				.country("KR")
				.state("Seoul")
				.city("Seoul")
				.district("Jongno")
				.street("Sejong-daero")
				.detail("110")
				.postalCode("03172")
				.latitude(37.5665)
				.longitude(126.9780)
				.build())
			.occupancyPolicy(OccupancyPolicy.builder()
				.maxOccupancy(4)
				.infantOccupancy(1)
				.petOccupancy(0)
				.build())
			.build();
			List<ReservationDateRange> projectedRanges = List.of(
				new ReservationDateRange(
					LocalDate.of(2026, 8, 12),
					LocalDate.of(2026, 8, 15)),
				new ReservationDateRange(
					LocalDate.of(2026, 8, 12),
					LocalDate.of(2026, 8, 15)),
				new ReservationDateRange(
					LocalDate.of(2026, 8, 10),
					LocalDate.of(2026, 8, 13))
			);
		when(accommodationRepository.findWithDetailsByAccommodationUid(accommodationUid))
			.thenReturn(Optional.of(accommodation));
		when(bookingWindowProvider.currentIndexingWindow()).thenReturn(window);
		when(reservationRepository
			.findActiveReservationRangesByAccommodationId(
				accommodation.getId(), window.startInclusive(), window.endExclusive()))
			.thenReturn(projectedRanges);

		AccommodationDocument document =
			documentBuilder.buildAccommodationDocument(accommodationUid);

		assertThat(document.reservationRanges()).containsExactly(
			new AccommodationDocument.DateRange(
				LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 15)),
			new AccommodationDocument.DateRange(
				LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 15)),
			new AccommodationDocument.DateRange(
				LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13))
		);
		assertThat(document.createdAt()).isEqualTo(Instant.parse("2026-08-01T09:00:00Z"));
		assertThat(document.timeZoneId()).isEqualTo("Asia/Seoul");
		verify(reservationRepository)
			.findActiveReservationRangesByAccommodationId(
				accommodation.getId(), window.startInclusive(), window.endExclusive());
	}
}
