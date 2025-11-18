package kr.kro.airbob.domain.accommodation.common;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public enum AccommodationType {
	ENTIRE_PLACE,      // 전체 숙소
	PRIVATE_ROOM,      // 개인실
	SHARED_ROOM,       // 다인실
	HOTEL_ROOM,        // 호텔 객실
	HOSTEL,            // 호스텔
	VILLA,             // 빌라
	GUESTHOUSE,        // 게스트하우스
	BNB,               // B&B
	RESORT,            // 리조트
	APARTMENT,         // 아파트
	HOUSE,             // 일반 주택
	TENT,              // 텐트
	BOAT,              // 보트
	TREEHOUSE,         // 트리하우스
	CAMPER_VAN,        // 캠핑카
	CASTLE             // 성 같은 특이한 숙소
	;

	public static List<AccommodationType> valuesOf(List<String> types) {
		return types.stream()
				.map(String::toUpperCase)
				.filter(AccommodationType::isValid)
				.map(AccommodationType::valueOf)
				.collect(Collectors.toList());
	}

	public static boolean isValid(String name) {
		try {
			AccommodationType.valueOf(name.toUpperCase().trim());
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
