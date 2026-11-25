package kr.kro.airbob.core.domain.recentlyviewed;

/** 회원별 최근 본 숙소 기록을 담는 Redis ZSET 키. 점수는 기록 시각(epoch millis)이다. */
final class RecentlyViewedKeys {

	private static final String PREFIX = "recently_viewed:";

	private RecentlyViewedKeys() {}

	static String of(Long memberId) {
		return PREFIX + memberId;
	}
}
