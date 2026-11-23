package kr.kro.airbob.domain.accommodation;

/** 숙소의 새 주소. 좌표와 시간대는 서버가 이 주소로 계산한다. */
public record NewAddress(
	String postalCode,
	String country,
	String state,
	String city,
	String district,
	String street,
	String detail
) {
}
