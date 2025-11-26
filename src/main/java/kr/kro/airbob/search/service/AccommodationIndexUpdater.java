package kr.kro.airbob.search.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.review.entity.AccommodationReviewSummary;
import kr.kro.airbob.domain.review.repository.AccommodationReviewSummaryRepository;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccommodationIndexUpdater {

	private static final String ACCOMMODATIONS = "accommodations";
	private final ElasticsearchOperations elasticsearchOperations;
	private final AccommodationReviewSummaryRepository reviewSummaryRepository;
	private final ReservationRepository reservationRepository;
	public void updateReviewSummaryInIndex(String accommodationUid) {
		AccommodationReviewSummary reviewSummary = reviewSummaryRepository.findByAccommodation_AccommodationUid(UUID.fromString(accommodationUid))
			.orElse(null);

		Map<String, Object> params = new HashMap<>();

		double averageRating = (reviewSummary != null && reviewSummary.getAverageRating() != null)
			? reviewSummary.getAverageRating().doubleValue()
			: 0.0;
		int reviewCount = reviewSummary != null ? reviewSummary.getTotalReviewCount() : 0;

		params.put("averageRating", averageRating);
		params.put("reviewCount", reviewCount);

		UpdateQuery updateQuery = UpdateQuery.builder(accommodationUid)
			.withScriptType(ScriptType.INLINE)
			.withScript(
				"ctx._source.averageRating = params.averageRating; ctx._source.reviewCount = params.reviewCount")
			.withParams(params)
			.build();

		elasticsearchOperations.update(updateQuery, IndexCoordinates.of(ACCOMMODATIONS));
	}

	public void updateReservedDatesInIndex(String accommodationUid) {
		List<LocalDate> reservedDates = getReservedDates(accommodationUid);

		List<String> reservedDateStrings = reservedDates.stream()
			.map(LocalDate::toString)
			.toList();

		Map<String, Object> params = new HashMap<>();
		params.put("reservedDates", reservedDateStrings);

		UpdateQuery updateQuery = UpdateQuery.builder(accommodationUid)
			.withScriptType(ScriptType.INLINE)
			.withScript("ctx._source.reservedDates = params.reservedDates")
			.withParams(params)
			.build();

		elasticsearchOperations.update(updateQuery, IndexCoordinates.of(ACCOMMODATIONS));
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
