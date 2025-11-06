package kr.kro.airbob.search.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccommodationSearchRequest {

	@Getter
	@Setter
	public static class AccommodationSearchRequestDto {

		// 목적지
		private String destination;

		// 가격 필터
		@Min(value = 0, message = "최소 가격은 0원 이상이어야 합니다")
		private Integer minPrice;
		@Min(value = 0, message = "최대 가격은 0원 이상이어야 합니다")
		private Integer maxPrice;

		// 날짜
		@FutureOrPresent
		private LocalDate checkIn;
		@Future
		private LocalDate checkOut;

		// 인원 수
		@PositiveOrZero(message = "성인 인원은 0명 이상이어야 합니다.")
		private Integer adultOccupancy;
		@PositiveOrZero(message = "아동 인원은 0명 이상이어야 합니다.")
		private Integer childOccupancy;
		@PositiveOrZero(message = "유아 인원은 0명 이상이어야 합니다.")
		private Integer infantOccupancy;
		@PositiveOrZero(message = "반려동물 인원은 0명 이상이어야 합니다.")
		private Integer petOccupancy;

		// 편의시설
		private List<String> amenityTypes;

		// 숙소 타입
		private List<String> accommodationTypes;

		@AssertTrue(message = "체크아웃 날짜는 체크인 날짜 다음날 이상이어야 합니다.")
		public boolean isValidDateRange() {
			if (checkIn != null && checkOut != null) {
				return checkOut.isAfter(checkIn);
			}
			return true;
		}

		@AssertTrue(message = "최대 가격은 최소 가격보다 크거나 같아야 합니다.")
		public boolean isValidPriceRange() {
			if (minPrice != null && maxPrice != null) {
				return minPrice <= maxPrice;
			}
			return true;
		}

		// 총 인원 수 계산 (유아, 펫 제외)
		public int getTotalGuests() {
			return (adultOccupancy != null ? adultOccupancy : 0) +
				(childOccupancy != null ? childOccupancy : 0);
		}

		public boolean hasPet() {
			return (petOccupancy != null) && petOccupancy > 0;
		}

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
	public static class MapBoundsDto {
		// 지도 드래그 영역
		private Double topLeftLat;
		private Double topLeftLng;
		private Double bottomRightLat;
		private Double bottomRightLng;

		@AssertTrue(message = "지도 범위(MapBounds)가 유효하지 않습니다.")
		public boolean isValid() {
			// 모든 값 null (지도 미사용)
			// 모든 값 null X (지도 사용)
			return (topLeftLat == null && topLeftLng == null && bottomRightLat == null && bottomRightLng == null) ||
				(topLeftLat != null && topLeftLng != null && bottomRightLat != null && bottomRightLng != null);
		}
	}
}
