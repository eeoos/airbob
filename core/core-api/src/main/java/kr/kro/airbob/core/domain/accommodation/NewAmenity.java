package kr.kro.airbob.core.domain.accommodation;

/** 숙소 편의시설 코드와 개수. 코드는 대소문자를 구분하지 않으며 저장할 때 대문자로 맞춘다. */
public record NewAmenity(
	String name,
	Integer count) {
}
