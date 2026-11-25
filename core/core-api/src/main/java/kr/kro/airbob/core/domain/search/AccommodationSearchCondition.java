package kr.kro.airbob.core.domain.search;

import java.time.LocalDate;
import java.util.List;

/**
 * 숙소 검색 조건. 값은 요청 그대로이고 비어 있으면 null이다.
 * amenityTypes·accommodationTypes는 받기만 하고 아직 검색에 반영하지 않는다.
 */
public record AccommodationSearchCondition(
	String destination,
	Integer minPrice,
	Integer maxPrice,
	LocalDate checkIn,
	LocalDate checkOut,
	Integer adultOccupancy,
	Integer childOccupancy,
	Integer infantOccupancy,
	Integer petOccupancy,
	List<String> amenityTypes,
	List<String> accommodationTypes,
	MapArea mapArea) {

	/** 지도 영역. 네 좌표가 모두 있을 때만 만든다. */
	public record MapArea(
		double topLeftLat,
		double topLeftLng,
		double bottomRightLat,
		double bottomRightLng) {
	}

	/** 성인이 1명 미만이면 성인 1명으로 보고, 비어 있는 다른 인원은 0으로 채운다. */
	public AccommodationSearchCondition withDefaultOccupancy() {
		int adults = adultOccupancy != null ? adultOccupancy : 0;
		if (adults >= 1) {
			return this;
		}
		return new AccommodationSearchCondition(
			destination, minPrice, maxPrice, checkIn, checkOut,
			1,
			childOccupancy != null ? childOccupancy : 0,
			infantOccupancy != null ? infantOccupancy : 0,
			petOccupancy != null ? petOccupancy : 0,
			amenityTypes, accommodationTypes, mapArea);
	}

	/** 성인과 아동 합계. 32비트 정수를 넘을 수 있어 double로 계산한다. */
	public double totalGuests() {
		return (adultOccupancy != null ? adultOccupancy.longValue() : 0L)
			+ (childOccupancy != null ? childOccupancy : 0);
	}

	public boolean hasPet() {
		return petOccupancy != null && petOccupancy > 0;
	}

	public boolean hasDateRange() {
		return checkIn != null && checkOut != null;
	}

	public boolean hasDestination() {
		return destination != null && !destination.trim().isEmpty();
	}
}
