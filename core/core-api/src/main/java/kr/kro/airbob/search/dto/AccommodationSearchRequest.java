package kr.kro.airbob.search.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationSearchRequest {
	public static final int DEFAULT_PAGE_SIZE = 18;
	public static final int MAX_PAGE_NUMBER = 14;

	@Getter
	@Setter
	@Schema(name = "AccommodationSearchRequestAccommodationSearchRequestDto")
	public static class AccommodationSearchRequestDto {

		@Schema(description = "목적지 텍스트. 도시·구/군·도로·상세 주소·국가·숙소 이름·설명에서 검색합니다. 공백은 무시하며 목적지와 지도 범위가 모두 없으면 빈 결과입니다.", example = "서울")
		private String destination;

		@Schema(description = "1박 기본 가격 최솟값(포함). 숙소 통화의 숫자 그대로 비교하며 통화 변환·할인·수수료는 반영하지 않습니다.", example = "100000")
		@Min(value = 0, message = "최소 가격은 0 이상이어야 합니다")
		private Integer minPrice;
		@Schema(description = "1박 기본 가격 최댓값(포함). minPrice 이상이어야 합니다.", example = "200000")
		@Min(value = 0, message = "최대 가격은 0 이상이어야 합니다")
		private Integer maxPrice;

		@Schema(description = "숙소 현지 체크인 날짜(YYYY-MM-DD). checkOut과 함께 입력합니다. 현지 오늘부터 3개월의 예약 가능 기간에 포함되는 숙소를 검색합니다.", example = "2027-01-10")
		private LocalDate checkIn;
		@Schema(description = "숙소 현지 체크아웃 날짜(YYYY-MM-DD). checkIn보다 뒤여야 하며 체크아웃 날은 숙박일에 포함하지 않습니다. 날짜를 모두 생략하면 예약 가능 여부를 필터링하지 않습니다.", example = "2027-01-12")
		private LocalDate checkOut;

		@Schema(description = "성인 수. 생략하거나 0이면 1명으로 보정하며 성인 + 아동으로 최대 수용 인원을 확인합니다.", example = "2")
		@PositiveOrZero(message = "성인 인원은 0명 이상이어야 합니다.")
		private Integer adultOccupancy;
		@Schema(description = "아동 수. 생략하면 0명이며 성인 수와 합산합니다.", example = "0")
		@PositiveOrZero(message = "아동 인원은 0명 이상이어야 합니다.")
		private Integer childOccupancy;
		@Schema(description = "유아 수. 생략하면 0명이며 성인 + 아동과 별도로 유아 수용 인원을 확인합니다.", example = "0")
		@PositiveOrZero(message = "유아 인원은 0명 이상이어야 합니다.")
		private Integer infantOccupancy;
		@Schema(description = "반려동물 수. 생략하면 0이며 별도로 반려동물 수용 수를 확인합니다.", example = "0")
		@PositiveOrZero(message = "반려동물 인원은 0명 이상이어야 합니다.")
		private Integer petOccupancy;

		@Schema(description = "미지원: 입력해도 검색 조건에 반영되지 않습니다.")
		private List<String> amenityTypes;

		@Schema(description = "미지원: 입력해도 검색 조건에 반영되지 않습니다.")
		private List<String> accommodationTypes;

		@AssertTrue(message = "체크인과 체크아웃 날짜는 함께 입력해야 하며, 체크아웃은 체크인보다 뒤여야 합니다.")
		@Schema(hidden = true)
		public boolean isValidDateRange() {
			return (checkIn == null && checkOut == null)
				|| (checkIn != null && checkOut != null && checkOut.isAfter(checkIn));
		}

		@AssertTrue(message = "최대 가격은 최소 가격보다 크거나 같아야 합니다.")
		@Schema(hidden = true)
		public boolean isValidPriceRange() {
			if (minPrice != null && maxPrice != null) {
				return minPrice <= maxPrice;
			}
			return true;
		}

		// 총 인원 수 계산 (유아, 펫 제외)
		@Schema(hidden = true)
		public double getTotalGuests() {
			return (adultOccupancy != null ? adultOccupancy.longValue() : 0L) +
				(childOccupancy != null ? childOccupancy : 0);
		}

		public boolean hasPet() {
			return (petOccupancy != null) && petOccupancy > 0;
		}

		@Schema(hidden = true)
		public boolean isValidOccupancy() {
			int adults = adultOccupancy != null ? adultOccupancy : 0;
			return adults >= 1;
		}

		public void setDefaultOccupancy() {
			if (adultOccupancy == null || adultOccupancy < 1) {
				this.adultOccupancy = 1;
			}
			if (childOccupancy == null) {
				this.childOccupancy = 0;
			}
			if (infantOccupancy == null) {
				this.infantOccupancy = 0;
			}
			if (petOccupancy == null) {
				this.petOccupancy = 0;
			}
		}
	}

	@Getter
	@Setter
	@Schema(name = "AccommodationSearchRequestMapBoundsDto")
	public static class MapBoundsDto {
		@Schema(description = "지도 좌상단 위도. 좌표 4개를 함께 입력하며 bottomRightLat 이상이어야 합니다.", minimum = "-90", maximum = "90", example = "37.7")
		private Double topLeftLat;
		@Schema(description = "지도 좌상단 경도. 날짜 변경선을 지나는 범위에서는 bottomRightLng보다 클 수 있습니다.", minimum = "-180", maximum = "180", example = "126.8")
		private Double topLeftLng;
		@Schema(description = "지도 우하단 위도. topLeftLat 이하여야 합니다.", minimum = "-90", maximum = "90", example = "37.4")
		private Double bottomRightLat;
		@Schema(description = "지도 우하단 경도. 모든 지도 좌표에는 유한한 숫자를 입력합니다.", minimum = "-180", maximum = "180", example = "127.2")
		private Double bottomRightLng;

		@AssertTrue(message = "지도 좌표는 4개를 함께 입력해야 하며, 위도 -90~90·경도 -180~180의 유한한 숫자이고 위쪽 위도가 아래쪽 이상이어야 합니다.")
		@Schema(hidden = true)
		public boolean isValid() {
			if (topLeftLat == null && topLeftLng == null && bottomRightLat == null && bottomRightLng == null) {
				return true;
			}
			return hasAllBounds()
				&& validCoordinate(topLeftLat, 90) && validCoordinate(bottomRightLat, 90)
				&& validCoordinate(topLeftLng, 180) && validCoordinate(bottomRightLng, 180)
				&& topLeftLat >= bottomRightLat;
		}

		private boolean validCoordinate(double coordinate, double limit) {
			return Double.isFinite(coordinate) && coordinate >= -limit && coordinate <= limit;
		}

		public boolean hasAllBounds() {
			return topLeftLat != null && topLeftLng != null && bottomRightLat != null && bottomRightLng != null;
		}
	}
}
