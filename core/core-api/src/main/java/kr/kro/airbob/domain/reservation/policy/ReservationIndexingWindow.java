package kr.kro.airbob.domain.reservation.policy;

import java.time.LocalDate;

public record ReservationIndexingWindow(
	LocalDate startInclusive,
	LocalDate endExclusive
) {
}
