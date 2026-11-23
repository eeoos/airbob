package kr.kro.airbob.domain.accommodation;

import java.time.LocalTime;

/** 숙소 기본 정보 부분 수정. null 필드는 유지한다. 주소·인원 정책·편의시설은 따로 바꾼다. */
public record AccommodationPatch(
	String name,
	String description,
	Long basePrice,
	String currency,
	String type,
	LocalTime checkInTime,
	LocalTime checkOutTime
) {
}
