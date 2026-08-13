package kr.kro.airbob.search.service;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;

import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationIndexingWindow;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 검색 인덱스 갱신기 단위 테스트")
class AccommodationIndexUpdaterTest {

	@Mock
	private ElasticsearchOperations elasticsearchOperations;

	@Mock
	private AccommodationReviewSummaryRepository reviewSummaryRepository;

	@Mock
	private ReservationRepository reservationRepository;

	@Mock
	private BookingWindowProvider bookingWindowProvider;

	@InjectMocks
	private AccommodationIndexUpdater indexUpdater;

	@Test
	@DisplayName("숙소 UID 예약 범위를 반열린 날짜 구간으로 갱신한다")
	void updateReservationRangesFromUidProjection() {
		UUID accommodationUid = UUID.fromString("8df7d116-42d1-44f4-87f5-ab87295caf23");
		ReservationIndexingWindow window = new ReservationIndexingWindow(
			LocalDate.of(2026, 8, 11), LocalDate.of(2026, 11, 13));
		when(bookingWindowProvider.currentIndexingWindow()).thenReturn(window);
		when(reservationRepository
			.findActiveReservationRangesByAccommodationUid(
				accommodationUid, window.startInclusive(), window.endExclusive()))
			.thenReturn(List.of(
				new ReservationDateRange(
					LocalDate.of(2026, 8, 12),
					LocalDate.of(2026, 8, 15)),
				new ReservationDateRange(
					LocalDate.of(2026, 8, 10),
					LocalDate.of(2026, 8, 13))
			));

		indexUpdater.updateReservationRangesInIndex(accommodationUid.toString());

		ArgumentCaptor<UpdateQuery> queryCaptor = ArgumentCaptor.forClass(UpdateQuery.class);
		ArgumentCaptor<IndexCoordinates> indexCaptor = ArgumentCaptor.forClass(IndexCoordinates.class);
		verify(elasticsearchOperations).update(queryCaptor.capture(), indexCaptor.capture());
		UpdateQuery updateQuery = queryCaptor.getValue();
		assertThat(updateQuery.getId()).isEqualTo(accommodationUid.toString());
		assertThat(updateQuery.getScriptType()).isEqualTo(ScriptType.INLINE);
		assertThat(updateQuery.getScript())
			.isEqualTo("ctx._source.reservationRanges = params.reservationRanges");
		assertThat(updateQuery.getParams()).containsOnly(entry("reservationRanges", List.of(
			Map.of("gte", "2026-08-12", "lt", "2026-08-15"),
			Map.of("gte", "2026-08-10", "lt", "2026-08-13")
		)));
		assertThat(indexCaptor.getValue().getIndexNames()).containsExactly("accommodations");
		verify(reservationRepository)
			.findActiveReservationRangesByAccommodationUid(
				accommodationUid, window.startInclusive(), window.endExclusive());
	}

	@Test
	@DisplayName("활성 예약이 없으면 기존 예약 범위를 빈 목록으로 초기화한다")
	void clearsReservationRangesWhenNoConfirmedReservationExists() {
		UUID accommodationUid = UUID.fromString("8df7d116-42d1-44f4-87f5-ab87295caf23");
		ReservationIndexingWindow window = new ReservationIndexingWindow(
			LocalDate.of(2026, 8, 11), LocalDate.of(2026, 11, 13));
		when(bookingWindowProvider.currentIndexingWindow()).thenReturn(window);
		when(reservationRepository
			.findActiveReservationRangesByAccommodationUid(
				accommodationUid, window.startInclusive(), window.endExclusive()))
			.thenReturn(List.of());

		indexUpdater.updateReservationRangesInIndex(accommodationUid.toString());

		ArgumentCaptor<UpdateQuery> queryCaptor = ArgumentCaptor.forClass(UpdateQuery.class);
		verify(elasticsearchOperations).update(queryCaptor.capture(), any(IndexCoordinates.class));
		assertThat(queryCaptor.getValue().getParams())
			.containsOnly(entry("reservationRanges", List.of()));
	}
}
