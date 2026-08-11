package kr.kro.airbob.search.service;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
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

	@InjectMocks
	private AccommodationIndexUpdater indexUpdater;

	@Test
	@DisplayName("숙소 UID 예약 범위의 모든 숙박일을 전역 중복 제거하고 정렬한 ISO 문자열로 갱신한다")
	void updateReservedDatesFromUidProjectionWithDistinctAscendingDates() {
		UUID accommodationUid = UUID.fromString("8df7d116-42d1-44f4-87f5-ab87295caf23");
		when(reservationRepository
			.findFutureConfirmedReservationRangesByAccommodationUid(accommodationUid))
			.thenReturn(List.of(
				new ReservationDateRange(
					LocalDateTime.of(2026, 8, 12, 15, 0),
					LocalDateTime.of(2026, 8, 15, 11, 0)),
				new ReservationDateRange(
					LocalDateTime.of(2026, 8, 10, 15, 0),
					LocalDateTime.of(2026, 8, 13, 11, 0))
			));

		indexUpdater.updateReservedDatesInIndex(accommodationUid.toString());

		ArgumentCaptor<UpdateQuery> queryCaptor = ArgumentCaptor.forClass(UpdateQuery.class);
		ArgumentCaptor<IndexCoordinates> indexCaptor = ArgumentCaptor.forClass(IndexCoordinates.class);
		verify(elasticsearchOperations).update(queryCaptor.capture(), indexCaptor.capture());
		UpdateQuery updateQuery = queryCaptor.getValue();
		assertThat(updateQuery.getId()).isEqualTo(accommodationUid.toString());
		assertThat(updateQuery.getScriptType()).isEqualTo(ScriptType.INLINE);
		assertThat(updateQuery.getScript())
			.isEqualTo("ctx._source.reservedDates = params.reservedDates");
		assertThat(updateQuery.getParams()).containsOnly(entry("reservedDates", List.of(
			"2026-08-10",
			"2026-08-11",
			"2026-08-12",
			"2026-08-13",
			"2026-08-14"
		)));
		assertThat(indexCaptor.getValue().getIndexNames()).containsExactly("accommodations");
		verify(reservationRepository)
			.findFutureConfirmedReservationRangesByAccommodationUid(accommodationUid);
	}
}
