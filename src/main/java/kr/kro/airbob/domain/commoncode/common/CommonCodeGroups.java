package kr.kro.airbob.domain.commoncode.common;

import java.util.Set;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 공통 코드 그룹 코드 상수
 * 매직 스트링 방지 + 사용처 추적용
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CommonCodeGroups {

	public static final String AMENITY_TYPE = "AMENITY_TYPE";
	public static final String ACCOMMODATION_TYPE = "ACCOMMODATION_TYPE";

	private static final Set<String> SUPPORTED_GROUPS = Set.of(
		AMENITY_TYPE,
		ACCOMMODATION_TYPE
	);

	public static boolean isSupported(String groupCode) {
		return groupCode != null && SUPPORTED_GROUPS.contains(groupCode);
	}
}
