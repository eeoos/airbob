package kr.kro.airbob.core.domain.recentlyviewed;

import java.time.Instant;

/** Redis에 남은 최근 본 숙소 기록 하나. */
public record RecentlyViewedEntry(
	Long accommodationId,
	Instant viewedAt
) {
}
