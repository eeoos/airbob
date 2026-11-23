package kr.kro.airbob.domain.accommodation;

/** 숙박 인원 정책. 생성과 전체 교체에 쓴다. */
public record NewOccupancyPolicy(
	Integer maxOccupancy,
	Integer infantOccupancy,
	Integer petOccupancy
) {
}
