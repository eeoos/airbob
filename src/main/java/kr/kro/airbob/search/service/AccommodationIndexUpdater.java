package kr.kro.airbob.search.service;

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
import kr.kro.airbob.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.domain.reservation.policy.ReservationIndexingWindow;
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
	private final BookingWindowProvider bookingWindowProvider;
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

	public void updateReservationRangesInIndex(String accommodationUid) {
		List<Map<String, String>> reservationRanges = getReservationRanges(accommodationUid);

		Map<String, Object> params = new HashMap<>();
		params.put("reservationRanges", reservationRanges);

		UpdateQuery updateQuery = UpdateQuery.builder(accommodationUid)
			.withScriptType(ScriptType.INLINE)
			.withScript("ctx._source.reservationRanges = params.reservationRanges")
			.withParams(params)
			.build();

		elasticsearchOperations.update(updateQuery, IndexCoordinates.of(ACCOMMODATIONS));
	}

	private List<Map<String, String>> getReservationRanges(String accommodationUid) {
		ReservationIndexingWindow window = bookingWindowProvider.currentIndexingWindow();
		return reservationRepository
			.findActiveReservationRangesByAccommodationUid(
				UUID.fromString(accommodationUid),
				window.startInclusive(),
				window.endExclusive()
			)
			.stream()
			.map(dateRange -> Map.of(
				"gte", dateRange.checkIn().toString(),
				"lt", dateRange.checkOut().toString()
			))
			.toList();
	}
}
