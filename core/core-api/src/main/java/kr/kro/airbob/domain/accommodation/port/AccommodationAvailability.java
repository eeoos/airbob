package kr.kro.airbob.domain.accommodation.port;

import java.time.LocalDate;
import java.util.List;

/** 숙소의 예약 가능 기간 [startInclusive, endExclusive)과 그 안의 예약 불가 구간. */
public record AccommodationAvailability(
	LocalDate startInclusive,
	LocalDate endExclusive,
	List<DateRange> unavailableRanges
) {

	public record DateRange(
		LocalDate startInclusive,
		LocalDate endExclusive
	) {
	}
}
