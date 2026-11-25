package kr.kro.airbob.core.domain.recentlyviewed;

import java.time.Instant;

import kr.kro.airbob.core.domain.accommodation.RecentlyViewedAccommodationProjection;

/** 회원이 최근 본 공개 숙소 하나. viewedAt은 마지막 기록 시각(밀리초 정밀도)이다. */
public record RecentlyViewedAccommodation(
	Instant viewedAt,
	RecentlyViewedAccommodationProjection accommodation,
	boolean wishlisted) {
}
