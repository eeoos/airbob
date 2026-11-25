package kr.kro.airbob.core.domain.search;

import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import kr.kro.airbob.core.domain.reservation.policy.BookingWindowProvider;
import kr.kro.airbob.core.domain.search.port.SearchWishlistReader;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccommodationSearchService {

	private final AccommodationDocumentReader documentReader;
	private final SearchWishlistReader wishlistReader;
	private final BookingWindowProvider bookingWindowProvider;

	public AccommodationSearchPage searchAccommodations(
		AccommodationSearchCondition condition,
		Pageable pageable,
		Long memberId) {
		AccommodationSearchCondition search = condition.withDefaultOccupancy();

		// 색인의 maxGuests는 32비트 정수이므로 이보다 큰 합계는 수용 가능한 숙소가 없다.
		if (search.totalGuests() > Integer.MAX_VALUE) {
			return AccommodationSearchPage.empty(pageable);
		}

		if (search.mapArea() == null && !search.hasDestination()) {
			return AccommodationSearchPage.empty(pageable);
		}

		Set<String> eligibleTimeZones = eligibleTimeZones(search);
		if (search.hasDateRange() && eligibleTimeZones.isEmpty()) {
			return AccommodationSearchPage.empty(pageable);
		}

		AccommodationDocumentHits hits = documentReader.search(search, eligibleTimeZones, pageable);
		List<AccommodationDocument> docs = hits.documents();
		if (docs.isEmpty()) {
			return AccommodationSearchPage.of(List.of(), pageable, hits.total());
		}

		List<Long> ids = docs.stream().map(AccommodationDocument::accommodationId).toList();
		Set<Long> wishlistIds = getWishlist(ids, memberId);

		List<AccommodationSearchItem> items = docs.stream()
			.map(d -> new AccommodationSearchItem(d, wishlistIds.contains(d.accommodationId())))
			.toList();
		return AccommodationSearchPage.of(items, pageable, hits.total());
	}

	private Set<String> eligibleTimeZones(AccommodationSearchCondition condition) {
		if (!condition.hasDateRange()) {
			return Set.of();
		}
		return bookingWindowProvider.eligibleTimeZonesForStay(condition.checkIn(), condition.checkOut());
	}

	private Set<Long> getWishlist(List<Long> ids, Long memberId) {
		if (memberId == null)
			return Set.of();
		return wishlistReader.findWishlistedAccommodationIds(memberId, ids);
	}
}
