package kr.kro.airbob.domain.accommodation;

/** 재고 seed 대상인 게시 숙소의 ID와 시간대. reservation의 재고 seed가 AccommodationReader로 읽는다. */
public interface InventorySeedTarget {

	Long getAccommodationId();

	String getTimeZoneId();
}
